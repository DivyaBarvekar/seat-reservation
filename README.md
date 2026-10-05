# seat-reservation

A seat-reservation API for a ticket on-sale: many buyers, a few hot seats, retries, and a hard
guarantee that **no seat is ever sold twice**. Spring Boot 4 + PostgreSQL, plain SQL via
`JdbcTemplate`, Flyway migrations, Prometheus metrics, JSON logs.

- **Live URL:** https://seat-reservation-f9y0.onrender.com
  ([readiness](https://seat-reservation-f9y0.onrender.com/actuator/health/readiness) ·
  [metrics](https://seat-reservation-f9y0.onrender.com/actuator/prometheus))
- **Live logs under load:** [screen recording of a burst against the live URL](https://drive.google.com/file/d/1muDyrB4Oo0TD2fPyLCOcWMT4bwmmwkjh/view?usp=sharing)
- **Design write-up:** [WRITEUP.md](WRITEUP.md) — the atomic decision, idempotency, holds,
  consistency under partition, what pages at 2am.

> If the live service is on Render's free plan it sleeps after 15 min idle; the first request
> then waits ~1 min while it boots. Data is unaffected (Postgres is a separate, always-on service).

## Run it locally

Requires Docker.

```bash
docker compose up --build        # Postgres + the app on http://localhost:8080
curl localhost:8080/actuator/health/readiness
```

That is the same image the deploy runs. To run from an IDE instead, start only the database
(`docker compose up db`) and run `SeatReservationApplication` (Java 21).

## Tests

```bash
./mvnw test
```

12 integration tests over real HTTP against a real PostgreSQL started by Testcontainers. They
cover the hot-seat race, per-user limit, idempotency, all-or-nothing multi-seat, cancel races,
identity and metrics. Needs Docker; without it they're **skipped** (not failed), so the build
still passes.

## Burst test (one command)

```bash
./burst.sh http://localhost:8080
ADMIN_TOKEN=<token> ./burst.sh https://<live-url> --concurrency 200
./burst.sh --help                # all options
```

Needs only `python3` (standard library). It creates a fresh show and fires 20,000 concurrent
reserve requests mixing: a hot-seat storm (≈1,500 users per seat), overlapping 2-seat requests in
both orders, random single seats, parallel retries with the same idempotency key, one key reused
with different seats, and users firing 10 parallel requests against a limit of 4. Then it checks,
and exits non-zero if anything fails:

- exactly one 201 per hot seat, no seat in two 201s, **zero 5xx**
- `available + held + confirmed == total_seats`, sampled during the burst and after
- the seats `GET /shows/{id}` reports confirmed are exactly the seats in 201 responses
- retries / key reuse / per-user limit behave as specified; identity can't be spoofed
- Prometheus counter deltas equal what the clients observed; gauges equal the API

Sample local run (Docker capped at 0.5 CPU / 512 MB):

```
burst: 20000 requests in 48.4s (413 req/s), latency p50 1106ms  p95 1599ms  p99 2901ms
outcome distribution:
    17562  409 seat_taken
     1682  201 confirmed
      450  200 idempotent_replay
      156  409 per_user_limit
      150  409 idempotency_key_reused
...
final state: available 271 + held 0 + confirmed 1729 = 2000  (total_seats 2000)
ALL CHECKS PASSED
```

## API

All bodies are JSON with `snake_case` fields. Money is integer paise.

| Method & path | Auth | Purpose |
|---|---|---|
| `POST /auth/token` | none | `{"user":"alice"}` → `{"token":"…","user_id":"alice"}` (demo login, 24h HMAC token) |
| `POST /shows` | `X-Admin-Token` | Create a show |
| `GET /shows/{id}` | none | Per-seat status + counts |
| `POST /shows/{id}/reserve` | `Bearer` | Reserve seats |
| `POST /reservations/{id}/cancel` | `Bearer` (owner) | Cancel and release seats |
| `GET /actuator/health/liveness` | none | Process is up |
| `GET /actuator/health/readiness` | none | Up **and** DB reachable (503 otherwise) |
| `GET /actuator/prometheus` | none | Metrics |

```bash
B=http://localhost:8080
SHOW=$(curl -s -XPOST $B/shows -H 'X-Admin-Token: local-admin-token' -H 'Content-Type: application/json' \
  -d '{"name":"Concert","seats":["A1","A2","A3"],"price_paise":25000,"per_user_limit":4}' | jq -r .id)
TOKEN=$(curl -s -XPOST $B/auth/token -H 'Content-Type: application/json' -d '{"user":"alice"}' | jq -r .token)

curl -s -XPOST $B/shows/$SHOW/reserve -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 7f9c…' -d '{"seats":["A1","A2"]}'
# 201 {"reservation_id":"…","show_id":"…","user_id":"alice","seats":["A1","A2"],"amount_paise":50000,"status":"confirmed"}

curl -s $B/shows/$SHOW
# {"id":"…","counts":{"available":1,"held":0,"confirmed":2},"total_seats":3,"seats":[{"seat":"A1","status":"confirmed"},…]}

curl -s -XPOST $B/reservations/<reservation_id>/cancel -H "Authorization: Bearer $TOKEN"
# 200 {…,"status":"cancelled"}
```

### Reserve semantics

- **Identity** comes only from the bearer token. A `user_id` in the body is ignored.
- **Idempotency key** is required, as the `Idempotency-Key` header or `idempotency_key` body
  field (if both are sent they must match). Keys are scoped per user.
- **All-or-nothing**: if any requested seat is unavailable, nothing is reserved.

| Status | `error` | Meaning |
|---|---|---|
| 201 | — | Reservation created |
| 200 | — | Retry with a used key and the same body: the original reservation, unchanged |
| 409 | `seat_taken` | A requested seat is held/confirmed by someone else |
| 409 | `per_user_limit` | Would exceed the show's `per_user_limit` |
| 409 | `idempotency_key_reused` | Key already used with a different show/seats |
| 409 | `contention` | Lock timeout/deadlock (should not happen; safe to retry) |
| 400 | `bad_request` | Missing key, unknown/duplicate/invalid seats, … |
| 401 | `unauthorized` | Missing/invalid token |
| 404 | `not_found` | Unknown show; or cancelling a reservation that isn't yours |
| 503 | `unavailable` | Database unreachable; nothing was reserved; retry with the same key |

Cancel is owner-only (someone else's reservation is a 404, so ids can't be probed) and
idempotent (cancelling twice returns the cancelled reservation).

## Observability

**Metrics** — `GET /actuator/prometheus`:

| Metric | Type | |
|---|---|---|
| `reservations_confirmed_total` | counter | Reservations created |
| `reservations_declined_total{reason}` | counter | `seat_taken`, `per_user_limit`, `idempotent_replay`, `idempotency_key_reused`, `contention`, `invalid_request` |
| `reservations_cancelled_total` | counter | Cancels (repeat cancels not counted) |
| `reservation_seats_confirmed_total` / `reservation_seats_released_total` | counter | Seat-level flow |
| `seats_available` / `seats_held` / `seats_confirmed` / `seats_capacity` `{show_id}` | gauge | From the DB every 5s |
| `http_server_requests_seconds_*` | histogram | Latency per endpoint/status |
| `hikaricp_connections_*` | gauge | DB pool usage / pending |

Counters are recorded after the transaction commits, so they reconcile with the API: over any
window, `Δreservation_seats_confirmed_total − Δreservation_seats_released_total == Δseats_confirmed`.

**Logs** — one JSON object per line (Elastic Common Schema) on stdout. Every line written while
serving a request carries `request_id` (from the caller's `X-Request-Id`, or generated, and echoed
back in the response header) and `user_id`. Each request gets an access line (`method`, `path`,
`status`, `duration_ms`) and each reservation outcome a line (`reservation confirmed` / `declined`
with `reason` / `replayed` / `cancelled`). On Render: service → **Logs**.

Render's logs aren't public, so here is a
[screen recording of the live logs during a burst against the live URL](https://drive.google.com/file/d/1muDyrB4Oo0TD2fPyLCOcWMT4bwmmwkjh/view?usp=sharing).

## Configuration

| Env var | Default | |
|---|---|---|
| `DATABASE_URL` | built from `DB_HOST`/`DB_PORT`/`DB_NAME` | Full JDBC URL (used by docker-compose) |
| `DB_HOST`, `DB_PORT`, `DB_NAME` | `localhost`, `5432`, `seats` | Used when `DATABASE_URL` is unset (Render) |
| `DATABASE_USER`, `DATABASE_PASSWORD` | `app`, `app` | |
| `AUTH_SECRET` | dev value | HMAC key for tokens, ≥ 32 chars. **Set in production.** |
| `ADMIN_TOKEN` | `local-admin-token` | Required to create shows. **Set in production.** |
| `DB_POOL_SIZE` | `20` | Hikari pool size |
| `DB_CONNECTION_TIMEOUT_MS` | `10000` | Wait for a pooled connection before 503 |
| `TOMCAT_MAX_THREADS` | `200` | Keep ≈2× `DB_POOL_SIZE` on small CPUs (see WRITEUP) |
| `TOMCAT_MAX_CONNECTIONS`, `TOMCAT_ACCEPT_COUNT` | `10000`, `2000` | Connection queue |
| `PORT` | `8080` | |

## Deploy (Render)

`render.yaml` is a Render Blueprint: **Dashboard → New → Blueprint → this repo**. It creates the
Postgres database and the Docker web service in one region, wires the DB credentials, generates
`AUTH_SECRET` and `ADMIN_TOKEN`, and uses `/actuator/health/readiness` as the health check, so
traffic is only routed once the DB is reachable. Find `ADMIN_TOKEN` under the service's
Environment tab to run `./burst.sh` against the live URL.

The image is tuned for small containers (CDS archive + C1 JIT): measured cold start at
0.1 CPU / 512 MB went from 246 s to ~60 s. See WRITEUP for the free vs starter numbers.

## Layout

```
src/main/java/com/divya/seatReservation/
  controller/   AuthController, ShowController, ReservationController (records metrics + outcome logs)
  service/      ReservationService (reserve/cancel transactions), ShowService, TokenService
  repository/   ReservationRepository (the atomic SQL), ShowRepository
  filter/       RequestIdFilter (correlation id, access log), AuthFilter (token -> identity)
  metrics/      ReservationMetrics (counters), SeatGauges (DB-backed gauges)
  exception/    ApiException, GlobalExceptionHandler (domain 4xx, 503 on DB loss, never a stray 500)
src/main/resources/db/migration/   Flyway schema
src/test/java/…/ReservationIntegrationTests.java   race / idempotency / cancel tests (Testcontainers)
scripts/burst.py, burst.sh          load + correctness audit
Dockerfile, docker-compose.yml, render.yaml
```
