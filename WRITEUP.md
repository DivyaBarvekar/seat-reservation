# Write-up

## 1. The atomic decision

**Mechanism: a conditional `UPDATE` guarded on current state, one row per seat, in a single
transaction.**

```sql
UPDATE seats SET status = 'confirmed', reservation_id = ?, user_id = ?
WHERE show_id = ? AND seat_no = ? AND status = 'available'
```

The decision "is this seat free, and if so it's mine" is one statement, not a read followed by a
write. Why it's race-free under PostgreSQL's default READ COMMITTED:

- Two transactions updating the same row serialise on the row lock. The second blocks until
  the first commits or rolls back.
- When it unblocks, Postgres **re-evaluates the `WHERE` clause against the newly committed
  row version**. The row now says `confirmed`, so the second update matches 0 rows. We check the
  row count; 0 means the seat is gone, and the request gets a 409 `seat_taken`.
- If the first transaction rolled back instead, the row is still `available` and the second
  one wins. Either way there is exactly one winner, and losers never see an error, only "0 rows".

A `CHECK ((status = 'available') = (reservation_id IS NULL))` constraint on `seats` backs this up
at the schema level: a seat can't be "confirmed" without belonging to a reservation.

**This was tested, not assumed.** I ran the burst script against a copy with the
`AND status = 'available'` guard removed. It immediately reported double-sold seats, with up to
3 winners on one hot seat. That happened even though the code still had a read-only
"is it available?" pre-check, which shows that a read-then-write check only narrows the race
window; it doesn't close it.

**Multi-seat requests: all-or-nothing, no deadlocks.** Seats are claimed one `UPDATE` at a
time in **sorted** seat order. The first one that matches 0 rows throws, and the whole
transaction rolls back: seats already claimed, the reservation row, the idempotency key and the
per-user count. Deadlock needs a cycle (A holds A1 and waits for A2, B holds A2 and waits for A1).
Because every transaction locks in the same global order, no cycle can form. That order is:

| Transaction | Lock order |
|---|---|
| reserve | idempotency key → per-user count → seats (sorted) |
| cancel | reservation row → per-user count → seats |

Both reserve and cancel take the per-user count lock before any seat lock, so a user reserving
and cancelling at the same moment can't deadlock either. The burst fires overlapping pairs in
*both* orders (`[A1,A2]` and `[A2,A1]`); every run has had 0 deadlocks and 0 5xx. As a backstop,
a lock timeout (5 s) or a deadlock error maps to a 409 `contention`, never a 500.

**Per-user limit**, also one atomic statement:

```sql
INSERT INTO user_show_counts (show_id, user_id, held) VALUES (?, ?, n)
ON CONFLICT (show_id, user_id) DO UPDATE SET held = user_show_counts.held + EXCLUDED.held
WHERE user_show_counts.held + EXCLUDED.held <= :limit
```

0 rows means over the limit, which gives a 409 `per_user_limit`. The row lock also serialises
one user's parallel requests for the same show, so 10 parallel reserves on a limit of 4 end with
exactly 4. If seat claiming then fails, the rollback undoes the increment.

**Fast decline.** In an on-sale burst most requests lose. After the idempotency check, a plain
read of the requested seats declines immediately if any is already taken, so losers skip the
per-user lock and the writes. This read can only *decline*; only the conditional `UPDATE` grants
a seat. It has to come after the idempotency check, otherwise the winner's own retry would see
"seat taken" instead of getting its reservation back.

## 2. Idempotency

- **Where:** table `idempotency_keys`, primary key `(user_id, idem_key)`, storing
  `request_hash = sha256(show_id | sorted seats)` and the resulting `reservation_id`. Keys are
  scoped per user, so one user's key can never collide with, or reveal, another's.
- **Exactly-once:** the first step of the reserve transaction is
  `INSERT … ON CONFLICT (user_id, idem_key) DO NOTHING`. The key, the seats and the reservation
  commit **atomically**, in one transaction, so a key is never stored without its reservation
  or the other way round.
  - **Two first attempts in flight at once:** the second `INSERT` blocks on the unique index
    until the first finishes. If the first commits, the second sees the conflict and replays.
    If it rolls back, the second proceeds as the first.
  - **Same key, same body** (same hash): returns the original reservation with **200** (not 201).
    So "exactly one 201 per hot seat" holds even counting retries.
  - **Same key, different body** (different show or seats): **409 `idempotency_key_reused`**.
- **Declines are not memoised.** A declined request rolls back, and its key with it, so
  retrying a decline re-evaluates. That's deliberate: a decline caused by your own per-user limit
  can legitimately succeed after you cancel something.
- **Ambiguous commits.** If the connection drops during `COMMIT`, the client gets a 503 and
  can't tell whether the reservation happened. Retrying with the same key resolves it: either
  replay of what did commit, or a fresh attempt.
- **Not done yet:** keys never expire. In production they'd get a TTL (e.g. 24 h) and a cleanup job.

## 3. Holds and expiry

**Model chosen: immediate confirm + explicit owner-only cancel.** `POST /shows/{id}/reserve`
confirms straight away (there's no payment step to wait for), and
`POST /reservations/{id}/cancel` releases. `held` exists in the schema and API but is always 0
in this model. The invariant counts it anyway, so adding holds doesn't change the contract.

Cancel, in one transaction:
1. `SELECT … FOR UPDATE` the reservation. Concurrent cancels of the same reservation serialise
   here. If it isn't yours, the result is **404, not 403**, so reservation IDs can't be probed.
   If it's already cancelled, it's returned unchanged.
2. Decrement the per-user count (the limit frees up).
3. Release only seats that **still point at this reservation**:
   `UPDATE seats SET status='available', reservation_id=NULL … WHERE reservation_id = ? AND status='confirmed'`.
   A seat that was released and re-booked by someone else points at *their* reservation, so it
   can't be touched. A release can never "resurrect" or steal a seat. If the number of rows
   released doesn't match the reservation's seat count, the transaction rolls back rather than
   corrupting the counts.

Tested: 50 concurrent cancels of one reservation gave 1 release. In 20 rounds of "owner
cancels while 300 users storm that seat", every round had exactly 1 new winner, and
`user_show_counts` matched the actual seats held for every user afterwards.

**Time-boxed holds (next step).** Add `expires_at` and a `held` state. Reserve would create a
hold; a payment confirm would run `UPDATE … SET status='confirmed' WHERE reservation_id=? AND status='held' AND expires_at > now()`.
A sweeper would release `WHERE status='held' AND expires_at < now()`. These are the same guarded
updates, so a late confirm and the sweeper can't both win.

## 4. Consistency vs availability under a partition

**This system chooses consistency.** PostgreSQL (a single primary) is the only source of truth
for seat state; the app keeps no seat state in memory. Only show metadata is cached, and shows
are immutable once created.

If the app can't reach the database:
- **Readiness turns 503**, so the platform stops routing traffic. Liveness stays 200, so the
  process isn't restarted for a problem that isn't its own.
- **Every request fails closed with 503 + `Retry-After`.** Nothing is ever reserved without a
  committed database write. Tested by killing the database mid-run: no 500s, and readiness
  recovered within about 1 s of the database returning.
- **During a burst, overload queues before it fails.** Tomcat threads are capped near the
  connection-pool size, so extra requests wait in Tomcat's queue (no timeout) rather than in the
  pool's queue (10 s timeout, then 503).

The cost is availability: while the database is unreachable, nobody can buy. For tickets that's
the right call. Overselling a seat is worse than a minute of 503s, and the idempotency key makes
retries safe. If we scaled out with read replicas, `GET /shows` could read from a replica
(possibly slightly stale counts), but **reserve and cancel must always go to the primary**.

## 5. Observability — what pages at 2am

Metrics, logs and the correlation ID are described in the README. Alerts:

| Page (wake someone) | Why |
|---|---|
| Any 5xx rate > 0 sustained for 2 min (`http_server_requests_seconds_count{status=~"5.."}`) | We designed for zero; 503s mean the DB is unreachable or the pool is exhausted |
| Readiness failing | The DB is unreachable, so nobody can buy |
| `seats_available + seats_held + seats_confirmed != seats_capacity` for any show | The invariant broke. Should be impossible; means a bug or manual DB edit |
| A double-sell detector finds anything: a scheduled query for seats whose reservation isn't `confirmed`, or user counts that disagree with actual seats held | Correctness, the one thing that must never happen |
| p99 reserve latency > 2 s for 5 min | Buyers are timing out; usually a slow DB or a lock-contention pile-up |
| `hikaricp_connections_pending` high with connection timeouts rising | About to start returning 503s |

| Ticket, don't page | |
|---|---|
| `reservations_declined_total{reason="contention"}` > 0 | Lock timeouts; shouldn't happen given lock ordering, so investigate |
| A spike in `seat_taken` or `per_user_limit` | That's the business working: an on-sale |

In an incident, start from a `request_id` in the logs. It ties a buyer's report to the exact
outcome line and access line.

## 6. Measured behaviour

Using `./burst.sh` (20k requests: hot seats, overlapping pairs, retries, key reuse, greedy users):

| Environment | Throughput | p99 | Result |
|---|---|---|---|
| Laptop, local Postgres | ~2,500 req/s | 0.6 s | all checks pass, 0 5xx |
| Docker capped at 0.5 CPU / 512 MB (≈ Render Starter) | 413 req/s | 2.9 s | all checks pass, 0 5xx |
| Docker capped at 0.1 CPU / 512 MB (≈ Render Free), 3k requests | 18 req/s | 14.6 s | all checks pass, 0 5xx |

**Automated tests** (`./mvnw test`, about 5 s): 12 integration tests over real HTTP against a real
Postgres (Testcontainers). They cover:
- a 200-user hot seat
- 10 parallel requests against a limit of 4
- the same key in parallel, and the same key with different seats
- 120 overlapping pairs sent in both orders
- partial requests
- spoofed identity
- owner-only, idempotent cancel
- a cancel racing a 100-user storm
- metrics only counting committed outcomes

With the status guard removed, 3 of them fail. Without Docker they're skipped, so a clean
clone still builds.

Two findings changed the configuration:
- **Container startup.** At 0.1 CPU, startup took 246 s. A CDS class archive built at image
  build time, plus the C1-only JIT, brought it to about 60 s and *doubled* throughput, because
  the C2 compiler's threads were competing with requests for a tenth of a CPU.
- **Thread-to-pool ratio.** With 100 Tomcat threads over a 15-connection pool at 0.1 CPU, requests
  waited more than 10 s for a connection and got 503s (112 in 3k). With 20 threads over 10
  connections, extra requests queued in Tomcat instead, and there were zero 503s.

## 7. AI usage

AI wrote most of the code in this repo. Here's how the work actually split.

**Phase 1: foundation (commits up to `25161be`).** I wrote it myself with help from an AI
assistant: the Spring Boot setup, Flyway schema, Docker/compose, health endpoints, HMAC token
auth with token-only identity, `POST`/`GET /shows`, and the global exception handler. The schema
from this phase is what the later work builds on. The `seats` table with its
`CHECK ((status = 'available') = (reservation_id IS NULL))`, the `idempotency_keys` table keyed
by `(user_id, idem_key)`, and the `user_show_counts` table were all in place before any
reservation logic was written.

**Phase 2: everything from the reserve endpoint on.** I used **Claude Code** (Anthropic's coding
agent, running in my terminal on this repo).

*What I directed:*
- I had it audit the repo against the brief and list what was missing. Then it worked in
  **small patches**, one feature at a time, and stopped after each one. **I reviewed and
  committed every patch myself.** It was instructed never to commit, so the history is the real
  order the work happened in.
- For each patch it wrote the code **and ran it against a real Postgres** before handing it over:
  functional checks, concurrent storms of thousands of requests, killing the database mid-run, and
  running the container under Render's free-tier CPU and memory limits.

*What the AI decided* (proposed by it, accepted by me after its explanation):
- The atomic mechanism: a conditional `UPDATE … WHERE status = 'available'` with a row-count
  check. Also the global lock order (key → per-user count → seats sorted), all-or-nothing for
  partial requests, and returning 200 rather than 201 for idempotent replays.
- The release model: explicit owner-only cancel rather than timed holds.
- The overload handling (bounded pools, the fast-decline pre-check, 503 on DB loss), the metric
  design (record after commit, no `show_id` label on counters), and the burst script's design.
- The deploy platform. I asked it to choose; it picked Render and the image tuning (CDS archive,
  C1-only JIT, thread-to-pool ratio) from measurements it ran.

*What I decided or questioned:*
- **Plain SQL vs JPA.** I questioned why we used `JdbcTemplate` with constructor injection
  rather than extending `JpaRepository`. After working through it I kept plain SQL. Every
  critical write here needs Postgres-specific atomic SQL (`ON CONFLICT`, conditional `UPDATE`),
  and JPA's `save()` is a read-then-write, which is exactly the race the brief warns about.
- **Scope.** The integration tests (Testcontainers) were the AI's suggestion. I checked whether
  the brief required them (it doesn't) and decided to keep them. They're also what fixed a
  clean-clone build that would otherwise fail without a local database.
- **Live-URL availability.** I raised that Render's free tier sleeps after 15 minutes while
  reviewers may test days later. That led to the plan-tier and keep-alive guidance in the README.
- **Delivery checks.** I had it re-audit the finished project against the full brief, which
  surfaced that reviewers would need the admin token for the live URL. I also caught that GitHub
  still showed a lowercase `dockerfile`; on my case-insensitive Mac the rename had to go
  through `git mv`.

*What the AI's own testing caught* (I checked the evidence):
- Killing the database mid-request raised exception types that weren't mapped yet. Those would
  have been 500s; now they're 503s.
- Prometheus silently drops a gauge named `seats_total` (`_total` is reserved for counters),
  so it's now `seats_capacity`.
- At 0.1 CPU the container took 246 s to start, and 100 threads over 15 DB connections produced
  503s. Both were fixed by measurement (section 6).
- It mutation-tested its own checks: removing the `status = 'available'` guard makes the burst
  script and 3 integration tests fail. So the tests can detect a double-sell, not just pass.

**What this means for the interview:** the design reasoning is written up in sections 1–4, and I
expect to explain and extend it live, starting from the reserve transaction in
`ReservationService` and the SQL in `ReservationRepository`.

## 8. What I'd do next

1. **CI.** The race tests exist (`./mvnw test`, Testcontainers, below), but nothing runs them
   automatically yet. Next would be a pipeline that runs them, then `docker compose up` +
   `./burst.sh` on every push.
2. **Holds with expiry plus a payment confirm step** (section 3), with `held` becoming real.
3. **Idempotency key TTL** and a cleanup job.
4. **Rate limiting per user/IP**, so one client can't saturate the pool. Real auth (OAuth/JWT
   from an identity provider) instead of the demo token endpoint.
5. **Scale-out readiness.** Metrics are per instance (sum across instances in Prometheus);
   `GET /shows` could be paginated and read from a replica; the show cache is safe with multiple
   instances because shows are immutable.
6. **The double-sell detector as a real scheduled job**, exporting a metric, instead of an
   alert rule in a document.
