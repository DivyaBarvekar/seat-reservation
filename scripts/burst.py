#!/usr/bin/env python3
"""
On-sale stampede against the seat reservation API, then a correctness audit.
Python 3.9+ standard library only; works against http:// and https:// URLs.

    ./burst.sh http://localhost:8080
    ./burst.sh https://<your-app>.onrender.com --requests 20000 --concurrency 500

What it fires (all shuffled together, `--concurrency` requests in flight at once):
  hot     many users storm the same few hot seats         -> exactly one 201 per hot seat
  pair    2-seat requests on overlapping seats, half in
          reversed order                                  -> all-or-nothing, no deadlock 5xx
  spread  random single seats until the show sells out    -> clean seat_taken declines
  retry   each user sends the SAME key 10x in parallel    -> one reservation, rest replay it
  reuse   each user sends one key with 4 different bodies -> one 201, rest 409 key reused
  greedy  each user fires 10 parallel reserves            -> at most per_user_limit succeed
Then, sequentially: a spoofed user_id in the body, cancelling someone else's reservation,
and cancelling your own.

What it checks (exit code 1 if any check fails):
  no seat in two 201s; zero 5xx; available+held+confirmed == total throughout the burst
  (sampled) and after; seats confirmed == seats in 201 responses; retry/reuse/limit rules;
  token-derived identity; and Prometheus counter deltas + gauges == what clients observed.
"""
import argparse
import asyncio
import collections
import json
import os
import random
import socket
import ssl
import sys
import time
import uuid
from urllib.parse import urlsplit

# ----------------------------------------------------------------------------- HTTP client


class Client:
    """Tiny asyncio HTTP/1.1 client: one connection per request, like independent buyers."""

    def __init__(self, base_url, timeout):
        u = urlsplit(base_url.rstrip("/"))
        if u.scheme not in ("http", "https") or not u.hostname:
            sys.exit(f"bad BASE_URL: {base_url!r} (expected http(s)://host[:port])")
        self.tls = u.scheme == "https"
        self.host, self.netloc, self.prefix = u.hostname, u.netloc, u.path
        self.port = u.port or (443 if self.tls else 80)
        self.ssl = ssl.create_default_context() if self.tls else None
        self.timeout = timeout
        self.addr = None  # resolved once; see resolve()

    async def resolve(self):
        """Look the host up ONCE. Resolving per connection makes hundreds of concurrent DNS
        lookups, which the OS resolver (notably macOS) starts failing - a client-side error
        that has nothing to do with the service. TLS still verifies against the hostname."""
        infos = await asyncio.get_running_loop().getaddrinfo(self.host, self.port, type=socket.SOCK_STREAM)
        self.addr = infos[0][4][0]

    async def request(self, method, path, body=None, headers=None):
        return await asyncio.wait_for(self._request(method, path, body, headers or {}), self.timeout)

    async def _request(self, method, path, body, headers):
        data = json.dumps(body).encode() if body is not None else b""
        h = {"Host": self.netloc, "Connection": "close", "Accept": "application/json",
             "Content-Length": str(len(data))}
        if body is not None:
            h["Content-Type"] = "application/json"
        h.update(headers)
        reader, writer = await asyncio.open_connection(
            self.addr or self.host, self.port, ssl=self.ssl, server_hostname=self.host if self.tls else None)
        try:
            head = f"{method} {self.prefix}{path} HTTP/1.1\r\n" + "".join(f"{k}: {v}\r\n" for k, v in h.items())
            writer.write(head.encode() + b"\r\n" + data)
            await writer.drain()

            raw_head = await reader.readuntil(b"\r\n\r\n")
            lines = raw_head.decode("latin-1").split("\r\n")
            status = int(lines[0].split(" ")[1])
            hdrs = {k.strip().lower(): v.strip() for k, v in (l.split(":", 1) for l in lines[1:] if ":" in l)}
            if hdrs.get("transfer-encoding", "").lower() == "chunked":
                raw = b""
                while True:
                    size = int((await reader.readline()).split(b";")[0].strip(), 16)
                    if size == 0:
                        break
                    raw += await reader.readexactly(size)
                    await reader.readexactly(2)  # CRLF after each chunk
            elif "content-length" in hdrs:
                raw = await reader.readexactly(int(hdrs["content-length"]))
            else:
                raw = await reader.read()
        finally:
            writer.close()
        try:
            payload = json.loads(raw) if raw.strip() else None
        except ValueError:
            payload = raw.decode("utf-8", "replace")
        return status, payload


# ----------------------------------------------------------------------------- the plan

Job = collections.namedtuple("Job", "kind user seats key extra")


def seat_labels(n):
    rows = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    per_row = -(-n // len(rows))  # ceil
    return [f"{rows[i // per_row]}{i % per_row + 1}" for i in range(n)]


def build_plan(a, seats, run):
    """Each scenario gets its own users and (where it matters) its own seats, so its
    expected outcome is exact regardless of what else is happening."""
    jobs, i = [], 0
    hot = seats[i:i + a.hot]; i += a.hot
    retry_seats = seats[i:i + a.retry_users]; i += a.retry_users
    reuse_seats = seats[i:i + a.reuse_users * 4]; i += a.reuse_users * 4
    greedy_seats = seats[i:i + a.greedy_users * 10]; i += a.greedy_users * 10
    spoof_seat = seats[i]; i += 1
    general = seats[i:]
    if len(general) < 50:
        sys.exit("--seats too small for the scenario mix")

    for u in range(a.retry_users):
        for _ in range(10):
            jobs.append(Job("retry", f"{run}-retry-{u}", [retry_seats[u]], f"retry-key-{u}", None))
    for u in range(a.reuse_users):
        for b in range(4):
            jobs.append(Job("reuse", f"{run}-reuse-{u}", [reuse_seats[u * 4 + b]], f"reuse-key-{u}", None))
    for u in range(a.greedy_users):
        for b in range(10):
            jobs.append(Job("greedy", f"{run}-greedy-{u}", [greedy_seats[u * 10 + b]], str(uuid.uuid4()), None))

    rest = max(0, a.requests - len(jobs))
    n_hot, n_pair = int(rest * 0.4), int(rest * 0.1)
    storm_user = lambda: f"{run}-storm-{random.randrange(a.users)}"
    for k in range(n_hot):
        jobs.append(Job("hot", storm_user(), [hot[k % len(hot)]], str(uuid.uuid4()), None))
    pair_zone = general[:max(20, len(general) // 10)]  # small zone => heavy overlap
    for k in range(n_pair):
        s = random.randrange(len(pair_zone) - 1)
        pair = [pair_zone[s], pair_zone[s + 1]]
        jobs.append(Job("pair", storm_user(), pair[::-1] if k % 2 else pair, str(uuid.uuid4()), None))
    for _ in range(rest - n_hot - n_pair):
        jobs.append(Job("spread", storm_user(), [random.choice(general)], str(uuid.uuid4()), None))

    random.shuffle(jobs)
    return jobs, hot, spoof_seat


# ----------------------------------------------------------------------------- helpers


def outcome(status, payload):
    """(status, reason) label for one reserve response."""
    if status == 201:
        return "201 confirmed"
    if status == 200:
        return "200 idempotent_replay"
    reason = payload.get("error") if isinstance(payload, dict) else None
    return f"{status} {reason or 'unknown'}"


def parse_prometheus(text):
    out = {}
    for line in text.splitlines():
        if line and not line.startswith("#"):
            name, _, value = line.rpartition(" ")
            try:
                out[name] = float(value)
            except ValueError:
                pass
    return out


class Report:
    def __init__(self):
        self.failed = 0

    def check(self, ok, label, detail=""):
        self.failed += 0 if ok else 1
        print(f"  [{'PASS' if ok else 'FAIL'}] {label}" + (f"  ({detail})" if detail else ""))

    def warn(self, label):
        print(f"  [WARN] {label}")


def pct(values, p):
    if not values:
        return 0
    s = sorted(values)
    return s[min(len(s) - 1, int(len(s) * p / 100))]


# ----------------------------------------------------------------------------- main


async def main(a):
    c = Client(a.base_url, a.timeout)
    run = uuid.uuid4().hex[:8]
    rep = Report()
    print(f"burst run {run} -> {a.base_url}")
    await c.resolve()

    # -- setup: health, show, tokens, metrics baseline
    st, _ = await c.request("GET", "/actuator/health/readiness")
    if st != 200:
        sys.exit(f"service not ready: GET /actuator/health/readiness -> {st}")

    seats = seat_labels(a.seats)
    st, show = await c.request("POST", "/shows",
                               {"name": f"burst-{run}", "seats": seats, "price_paise": a.price_paise,
                                "per_user_limit": a.per_user_limit},
                               {"X-Admin-Token": a.admin_token})
    if st != 201:
        sys.exit(f"could not create show: {st} {show}")
    show_id = show["id"]
    jobs, hot, spoof_seat = build_plan(a, seats, run)
    users = sorted({j.user for j in jobs} | {f"{run}-spoofer"})
    print(f"show {show_id}: {a.seats} seats, per_user_limit {a.per_user_limit}, "
          f"{len(hot)} hot seats; {len(jobs)} reserve requests from {len(users)} users")

    sem = asyncio.Semaphore(a.concurrency)

    async def mint(u):
        # Setup, not the test: retry transient network errors so they can't abort the run.
        for attempt in range(5):
            try:
                async with sem:
                    s, b = await c.request("POST", "/auth/token", {"user": u})
                break
            except (OSError, asyncio.TimeoutError) as e:
                if attempt == 4:
                    sys.exit(f"token for {u} failed after retries: {type(e).__name__}: {e}")
                await asyncio.sleep(0.5 * (attempt + 1))
        if s != 200:
            sys.exit(f"token for {u} failed: {s} {b}")
        return u, b["token"]
    tokens = dict(await asyncio.gather(*(mint(u) for u in users)))

    async def scrape():
        try:
            s, body = await c.request("GET", "/actuator/prometheus", headers={"Accept": "text/plain"})
            return parse_prometheus(body) if s == 200 and isinstance(body, str) else None
        except Exception:
            return None
    metrics_before = await scrape()

    # -- the burst, with an invariant sampler running alongside
    results = [None] * len(jobs)
    latencies = []
    burst_done = asyncio.Event()
    samples = {"n": 0, "bad": [], "errors": 0}

    async def fire(idx, job):
        body = {"seats": job.seats, "idempotency_key": job.key}
        async with sem:
            t0 = time.monotonic()
            try:
                s, b = await c.request("POST", f"/shows/{show_id}/reserve", body,
                                       {"Authorization": f"Bearer {tokens[job.user]}"})
            except Exception as e:  # timeouts, resets: reported, never silently dropped
                s, b = 0, {"error": type(e).__name__}
            latencies.append(time.monotonic() - t0)
        results[idx] = (s, b)

    async def sampler():
        while not burst_done.is_set():
            try:
                s, b = await c.request("GET", f"/shows/{show_id}")
                if s == 200:
                    samples["n"] += 1
                    cn = b["counts"]
                    if cn["available"] + cn["held"] + cn["confirmed"] != b["total_seats"]:
                        samples["bad"].append(cn)
                else:
                    samples["errors"] += 1
            except Exception:
                samples["errors"] += 1
            await asyncio.sleep(0.2)

    t0 = time.monotonic()
    sampler_task = asyncio.create_task(sampler())
    await asyncio.gather(*(fire(i, j) for i, j in enumerate(jobs)))
    elapsed = time.monotonic() - t0
    burst_done.set()
    await sampler_task

    # -- outcome distribution
    dist = collections.Counter(outcome(s, b) if s else f"ERR {b['error']}" for s, b in results)
    print(f"\nburst: {len(jobs)} requests in {elapsed:.1f}s ({len(jobs) / elapsed:.0f} req/s), "
          f"latency p50 {pct(latencies, 50) * 1000:.0f}ms  p95 {pct(latencies, 95) * 1000:.0f}ms  "
          f"p99 {pct(latencies, 99) * 1000:.0f}ms")
    print("outcome distribution:")
    for k, v in sorted(dist.items(), key=lambda kv: -kv[1]):
        print(f"  {v:>7}  {k}")
    by_kind = collections.defaultdict(collections.Counter)
    for j, (s, b) in zip(jobs, results):
        by_kind[j.kind][outcome(s, b) if s else "ERR"] += 1
    print("by scenario:")
    for kind in ("hot", "pair", "spread", "retry", "reuse", "greedy"):
        print(f"  {kind:<7} " + ", ".join(f"{k}: {v}" for k, v in by_kind[kind].most_common()))

    # -- checks
    print("\nchecks:")
    fivexx = sum(v for k, v in dist.items() if k[0] == "5")
    transport = sum(v for k, v in dist.items() if k.startswith("ERR"))
    rep.check(fivexx == 0, "zero 5xx across the burst", f"{fivexx} 5xx")
    if transport:
        rep.warn(f"{transport} requests got no HTTP response (client timeout/reset) - see distribution")

    confirmed_seats = collections.Counter(s for (st, b) in results if st == 201 for s in b["seats"])
    doubles = [s for s, n in confirmed_seats.items() if n > 1]
    rep.check(not doubles, "no seat appears in two 201 responses", f"double-sold: {doubles[:10]}" if doubles else "")

    for seat in hot:
        wins = sum(1 for j, (st, _) in zip(jobs, results) if j.kind == "hot" and j.seats == [seat] and st == 201)
        tries = sum(1 for j in jobs if j.kind == "hot" and j.seats == [seat])
        rep.check(wins == 1, f"hot seat {seat}: exactly one 201 out of {tries} attempts", f"{wins} winners")

    rep.check(not samples["bad"],
              f"invariant held in all {samples['n']} mid-burst samples (available+held+confirmed == total)",
              f"violations: {samples['bad'][:3]}" if samples["bad"] else "")
    if samples["n"] == 0:
        rep.warn("no mid-burst invariant samples completed")

    def per_user(kind):
        g = collections.defaultdict(list)
        for j, r in zip(jobs, results):
            if j.kind == kind:
                g[j.user].append(r)
        return g

    bad = []
    for u, rs in per_user("retry").items():
        ids = {b.get("reservation_id") for st, b in rs if st in (200, 201)}
        if sum(1 for st, _ in rs if st == 201) != 1 or len(ids) != 1 or any(st not in (200, 201) for st, _ in rs):
            bad.append((u, [st for st, _ in rs]))
    rep.check(not bad, f"same key x10 in parallel -> one reservation, 9 replays ({a.retry_users} users)",
              f"{bad[:3]}" if bad else "")

    bad = []
    for u, rs in per_user("reuse").items():
        reasons = sorted(outcome(st, b) for st, b in rs)
        if reasons != ["201 confirmed"] + ["409 idempotency_key_reused"] * 3:
            bad.append((u, reasons))
    rep.check(not bad, f"same key, different seats -> one 201, rest 409 ({a.reuse_users} users)",
              f"{bad[:2]}" if bad else "")

    bad = []
    for u, rs in per_user("greedy").items():
        wins = sum(1 for st, _ in rs if st == 201)
        if wins > a.per_user_limit:
            bad.append((u, wins))
    rep.check(not bad, f"10 parallel reserves never exceed per_user_limit={a.per_user_limit} "
                       f"({a.greedy_users} users)", f"{bad[:3]}" if bad else "")

    st, final = await c.request("GET", f"/shows/{show_id}")
    api_confirmed = {s["seat"] for s in final["seats"] if s["status"] == "confirmed"}
    rep.check(api_confirmed == set(confirmed_seats),
              "seats confirmed in GET /shows == seats in 201 responses",
              f"api {len(api_confirmed)} vs clients {len(confirmed_seats)}")

    # -- identity: token-derived only
    spoofer = f"{run}-spoofer"
    victim_res = next((b for (st, b) in results if st == 201), None)
    st, b = await c.request("POST", f"/shows/{show_id}/reserve",
                            {"seats": [spoof_seat], "idempotency_key": "spoof", "user_id": victim_res["user_id"]},
                            {"Authorization": f"Bearer {tokens[spoofer]}"})
    spoof_ok = st == 201 and b["user_id"] == spoofer
    rep.check(spoof_ok, "spoofed user_id in body is ignored; reservation belongs to the token's user",
              f"{st} user_id={b.get('user_id') if isinstance(b, dict) else b}")
    st2, _ = await c.request("POST", f"/reservations/{victim_res['reservation_id']}/cancel", None,
                             {"Authorization": f"Bearer {tokens[spoofer]}"})
    rep.check(st2 == 404, "cannot cancel another user's reservation", f"got {st2}")
    cancelled = 0
    if spoof_ok:
        st3, b3 = await c.request("POST", f"/reservations/{b['reservation_id']}/cancel", None,
                                  {"Authorization": f"Bearer {tokens[spoofer]}"})
        cancelled = 1 if st3 == 200 else 0
        rep.check(st3 == 200 and b3["status"] == "cancelled", "owner can cancel their own reservation", f"got {st3}")

    # -- final reconciliation
    st, final = await c.request("GET", f"/shows/{show_id}")
    cn = final["counts"]
    total_ok = cn["available"] + cn["held"] + cn["confirmed"] == final["total_seats"]
    print(f"\nfinal state: available {cn['available']} + held {cn['held']} + confirmed {cn['confirmed']} "
          f"= {cn['available'] + cn['held'] + cn['confirmed']}  (total_seats {final['total_seats']})")
    rep.check(total_ok, "reconciliation invariant holds after the burst")
    spoof_status = next(s["status"] for s in final["seats"] if s["seat"] == spoof_seat)
    rep.check(spoof_status == "available", "a cancelled seat is available again", f"{spoof_seat} is {spoof_status}")

    # -- metrics: counter deltas must equal what clients saw; gauges must equal the API
    print("\nmetrics reconciliation (valid when no other traffic hits the service during the run):")
    if metrics_before is None:
        rep.warn("/actuator/prometheus not reachable - skipped")
    else:
        spoof_201 = 1 if spoof_ok else 0
        n201 = dist.get("201 confirmed", 0) + spoof_201
        expected = {
            "reservations_confirmed_total": n201,
            "reservation_seats_confirmed_total": sum(confirmed_seats.values()) + spoof_201,
            "reservations_cancelled_total": cancelled,
            "reservation_seats_released_total": cancelled,
        }
        for reason in ("seat_taken", "per_user_limit", "idempotency_key_reused", "contention"):
            expected[f'reservations_declined_total{{reason="{reason}"}}'] = dist.get(f"409 {reason}", 0)
        expected['reservations_declined_total{reason="idempotent_replay"}'] = dist.get("200 idempotent_replay", 0)

        after = await scrape()
        for name, want in expected.items():
            got = after.get(name, 0) - metrics_before.get(name, 0)
            rep.check(abs(got - want) < 0.5, f"{name} +{got:.0f}", f"clients observed {want}")

        gauge = lambda m, n: m.get(f'seats_{n}{{show_id="{show_id}"}}')
        deadline = time.monotonic() + a.gauge_wait
        while True:  # gauges refresh on a timer; give them a moment to catch up
            g = {n: gauge(after, n) for n in ("available", "held", "confirmed", "capacity")}
            want = {"available": cn["available"], "held": cn["held"], "confirmed": cn["confirmed"],
                    "capacity": final["total_seats"]}
            if g == want or time.monotonic() > deadline:
                break
            await asyncio.sleep(1)
            after = await scrape()
        rep.check(g == want, "seat gauges match GET /shows", f"gauges {g}")

    print(f"\n{'ALL CHECKS PASSED' if rep.failed == 0 else f'{rep.failed} CHECK(S) FAILED'}")
    return 1 if rep.failed else 0


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("base_url", help="e.g. http://localhost:8080 or https://your-app.onrender.com")
    p.add_argument("--requests", type=int, default=20000, help="total reserve requests (default 20000)")
    p.add_argument("--concurrency", type=int, default=500, help="requests in flight at once (default 500)")
    p.add_argument("--seats", type=int, default=2000, help="seats in the show (default 2000)")
    p.add_argument("--hot", type=int, default=5, help="number of hot seats to storm (default 5)")
    p.add_argument("--users", type=int, default=1000, help="distinct users in the storm (default 1000)")
    p.add_argument("--retry-users", type=int, default=50)
    p.add_argument("--reuse-users", type=int, default=50)
    p.add_argument("--greedy-users", type=int, default=20)
    p.add_argument("--per-user-limit", type=int, default=4)
    p.add_argument("--price-paise", type=int, default=25000)
    p.add_argument("--timeout", type=float, default=120, help="per-request timeout seconds")
    p.add_argument("--gauge-wait", type=float, default=15, help="seconds to wait for gauges to refresh")
    p.add_argument("--admin-token", default=os.environ.get("ADMIN_TOKEN", "local-admin-token"),
                   help="X-Admin-Token for creating the show (default: $ADMIN_TOKEN or local-admin-token)")
    sys.exit(asyncio.run(main(p.parse_args())))
