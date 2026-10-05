package com.divya.seatReservation;

import com.divya.seatReservation.service.TokenService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.json.JsonParser;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end over real HTTP against a real Postgres: the filters, auth, exception mapping and
 * metrics are all in the path. Concurrent tests release every request at once through a latch
 * so they genuinely race on the same rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class ReservationIntegrationTests {

    @Value("${local.server.port}") int port;
    @Value("${app.auth.admin-token}") String adminToken;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry meters;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final JsonParser json = JsonParserFactory.getJsonParser();

    // ------------------------------------------------------------------ the correctness bar

    @Test
    void hotSeat_exactlyOneWinner_everyoneElseCleanDecline() {
        String show = createShow(seats(10), 4);

        List<Resp> rs = concurrently(200, i -> reserve(show, token("hot-" + i), key(), "A1"));

        assertNo5xx(rs);
        assertThat(count(rs, 201)).isEqualTo(1);
        assertThat(count(rs, 409, "seat_taken")).isEqualTo(199);
        String winner = (String) rs.stream().filter(r -> r.status == 201).findFirst().orElseThrow().body.get("user_id");
        assertThat(seatOwner(show, "A1")).isEqualTo(winner);
        assertInvariant(show);
    }

    @Test
    void perUserLimit_holdsUnderParallelRequests() {
        String show = createShow(seats(20), 4);
        String t = token("greedy");

        List<Resp> rs = concurrently(10, i -> reserve(show, t, key(), "A" + (i + 1)));

        assertNo5xx(rs);
        assertThat(count(rs, 201)).isEqualTo(4);
        assertThat(count(rs, 409, "per_user_limit")).isEqualTo(6);
        assertThat(heldCount(show, "greedy")).isEqualTo(4);
        assertThat(confirmedSeatsOf(show, "greedy")).isEqualTo(4);
        assertInvariant(show);
    }

    @Test
    void sameKeyInParallel_reservesExactlyOnce() {
        String show = createShow(seats(10), 4);
        String t = token("retrier");
        String k = key();

        List<Resp> rs = concurrently(20, i -> reserve(show, t, k, "A3"));

        assertNo5xx(rs);
        assertThat(count(rs, 201)).isEqualTo(1);
        assertThat(count(rs, 200)).isEqualTo(19);
        assertThat(rs.stream().map(r -> r.body.get("reservation_id")).distinct()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations WHERE show_id = ?::uuid AND user_id = 'retrier'",
                Integer.class, show)).isEqualTo(1);
        assertInvariant(show);
    }

    @Test
    void sameKeyDifferentSeats_isRejected_sequentialAndConcurrent() {
        String show = createShow(seats(10), 4);
        String t = token("reuser");
        String k = key();

        assertThat(reserve(show, t, k, "A1").status).isEqualTo(201);
        Resp again = reserve(show, t, k, "A2");
        assertThat(again.status).isEqualTo(409);
        assertThat(again.body.get("error")).isEqualTo("idempotency_key_reused");
        assertThat(seatStatus(show, "A2")).isEqualTo("available");

        String k2 = key();
        List<Resp> rs = concurrently(4, i -> reserve(show, t, k2, "A" + (i + 5)));
        assertNo5xx(rs);
        assertThat(count(rs, 201)).isEqualTo(1);
        assertThat(count(rs, 409, "idempotency_key_reused")).isEqualTo(3);
        assertInvariant(show);
    }

    @Test
    void overlappingMultiSeatRequests_allOrNothing_noDeadlock() {
        String show = createShow(seats(4), 4);
        List<List<String>> pairs = List.of(List.of("A1", "A2"), List.of("A2", "A1"), List.of("A2", "A3"),
                List.of("A3", "A2"), List.of("A3", "A4"), List.of("A4", "A3"));

        List<Resp> rs = concurrently(120, i -> reserve(show, token("pair-" + i), key(),
                pairs.get(i % pairs.size()).toArray(String[]::new)));

        assertNo5xx(rs);  // a deadlock would surface as 409 contention or a 5xx; neither may happen
        assertThat(count(rs, 409, "contention")).isZero();
        List<String> sold = rs.stream().filter(r -> r.status == 201)
                .flatMap(r -> ((List<?>) r.body.get("seats")).stream().map(Object::toString)).toList();
        assertThat(sold).doesNotHaveDuplicates();
        // every winning reservation owns ALL its seats; no seat is claimed by a losing request
        for (Resp r : rs) {
            if (r.status != 201) continue;
            for (Object seat : (List<?>) r.body.get("seats")) {
                assertThat(seatReservation(show, seat.toString())).isEqualTo(r.body.get("reservation_id"));
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seats WHERE show_id = ?::uuid AND status = 'confirmed'",
                Integer.class, show)).isEqualTo(sold.size());
        assertInvariant(show);
    }

    @Test
    void partialRequest_reservesNothing() {
        String show = createShow(seats(10), 4);
        assertThat(reserve(show, token("bob"), key(), "A1").status).isEqualTo(201);

        Resp r = reserve(show, token("alice"), key(), "A1", "A2");

        assertThat(r.status).isEqualTo(409);
        assertThat(r.body.get("error")).isEqualTo("seat_taken");
        assertThat(seatStatus(show, "A2")).isEqualTo("available");
        assertThat(heldCount(show, "alice")).isZero();
        assertInvariant(show);
    }

    // ------------------------------------------------------------------ identity & cancel

    @Test
    void identityComesFromToken_notFromBody() {
        String show = createShow(seats(10), 4);
        String body = "{\"seats\":[\"A1\"],\"idempotency_key\":\"" + key() + "\",\"user_id\":\"victim\"}";

        Resp r = send("POST", "/shows/" + show + "/reserve", token("mallory"), body);

        assertThat(r.status).isEqualTo(201);
        assertThat(r.body.get("user_id")).isEqualTo("mallory");
        assertThat(seatOwner(show, "A1")).isEqualTo("mallory");
    }

    @Test
    void cancel_ownerOnly_releasesSeat_rebookable_idempotent() {
        String show = createShow(seats(10), 4);
        Resp mine = reserve(show, token("owner"), key(), "A1", "A2");
        String id = (String) mine.body.get("reservation_id");

        assertThat(send("POST", "/reservations/" + id + "/cancel", token("intruder"), null).status).isEqualTo(404);
        assertThat(seatStatus(show, "A1")).isEqualTo("confirmed");

        Resp c = send("POST", "/reservations/" + id + "/cancel", token("owner"), null);
        assertThat(c.status).isEqualTo(200);
        assertThat(c.body.get("status")).isEqualTo("cancelled");
        assertThat(seatStatus(show, "A1")).isEqualTo("available");
        assertThat(heldCount(show, "owner")).isZero();

        Resp twice = send("POST", "/reservations/" + id + "/cancel", token("owner"), null);
        assertThat(twice.status).isEqualTo(200);
        assertThat(heldCount(show, "owner")).isZero();  // not decremented twice

        assertThat(reserve(show, token("next"), key(), "A1").status).isEqualTo(201);
        assertInvariant(show);
    }

    @Test
    void cancelRacingAStorm_neverDoubleSells_andCountsStayConsistent() {
        String show = createShow(seats(10), 4);
        String id = (String) reserve(show, token("holder"), key(), "A1").body.get("reservation_id");

        List<Resp> rs = concurrently(101, i -> i == 0
                ? send("POST", "/reservations/" + id + "/cancel", token("holder"), null)
                : reserve(show, token("racer-" + i), key(), "A1"));

        assertNo5xx(rs);
        assertThat(rs.get(0).status).isEqualTo(200);
        long winners = rs.subList(1, rs.size()).stream().filter(r -> r.status == 201).count();
        assertThat(winners).isLessThanOrEqualTo(1);  // 0 if every racer ran before the cancel committed
        assertThat(seatStatus(show, "A1")).isEqualTo(winners == 1 ? "confirmed" : "available");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM user_show_counts c
                WHERE c.show_id = ?::uuid AND c.held <> (SELECT count(*) FROM seats s
                      WHERE s.show_id = c.show_id AND s.user_id = c.user_id AND s.status = 'confirmed')
                """, Integer.class, show)).isZero();
        assertInvariant(show);
    }

    // ------------------------------------------------------------------ edges & metrics

    @Test
    void rejectsMissingTokenAndMissingKey() {
        String show = createShow(seats(2), 4);
        assertThat(send("POST", "/shows/" + show + "/reserve", null, "{\"seats\":[\"A1\"],\"idempotency_key\":\"k\"}").status)
                .isEqualTo(401);
        Resp noKey = send("POST", "/shows/" + show + "/reserve", token("u"), "{\"seats\":[\"A1\"]}");
        assertThat(noKey.status).isEqualTo(400);
        assertThat(seatStatus(show, "A1")).isEqualTo("available");
    }

    @Test
    void rootUrlDescribesTheService() {
        Resp r = send("GET", "/", null, null);
        assertThat(r.status).isEqualTo(200);
        assertThat(r.body.get("service")).isEqualTo("seat-reservation");
        assertThat(((Map<?, ?>) r.body.get("endpoints")).containsKey("POST /shows/{id}/reserve")).isTrue();
    }

    @Test
    void metricsCountOnlyWhatActuallyHappened() {
        String show = createShow(seats(4), 4);
        double confirmed = counter("reservations.confirmed", null);
        double taken = counter("reservations.declined", "seat_taken");
        double replay = counter("reservations.declined", "idempotent_replay");

        String k = key();
        reserve(show, token("m1"), k, "A1");  // confirmed
        reserve(show, token("m1"), k, "A1");  // replay
        reserve(show, token("m2"), key(), "A1");  // seat_taken

        assertThat(counter("reservations.confirmed", null) - confirmed).isEqualTo(1);
        assertThat(counter("reservations.declined", "idempotent_replay") - replay).isEqualTo(1);
        assertThat(counter("reservations.declined", "seat_taken") - taken).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    record Resp(int status, Map<String, Object> body) {}

    private String createShow(List<String> seats, int limit) {
        String body = "{\"name\":\"t\",\"seats\":" + jsonArray(seats) + ",\"price_paise\":25000,\"per_user_limit\":" + limit + "}";
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/shows"))
                .header("Content-Type", "application/json").header("X-Admin-Token", adminToken)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        Resp r = exchange(req);
        assertThat(r.status).isEqualTo(201);
        return (String) r.body.get("id");
    }

    private Resp reserve(String show, String token, String key, String... seats) {
        return send("POST", "/shows/" + show + "/reserve", token,
                "{\"seats\":" + jsonArray(List.of(seats)) + ",\"idempotency_key\":\"" + key + "\"}");
    }

    private Resp send(String method, String path, String token, String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return exchange(b.build());
    }

    private Resp exchange(HttpRequest req) {
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> body = res.body().isBlank() ? Map.of() : json.parseMap(res.body());
            return new Resp(res.statusCode(), body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Runs n requests with all of them released at the same instant. Results in index order. */
    private List<Resp> concurrently(int n, IntFunction<Resp> request) {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Resp>> futures = IntStream.range(0, n)
                    .mapToObj(i -> pool.submit(() -> { go.await(); return request.apply(i); })).toList();
            go.countDown();
            List<Resp> out = new ArrayList<>();
            for (Future<Resp> f : futures) out.add(f.get(60, TimeUnit.SECONDS));
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertInvariant(String show) {
        Resp r = send("GET", "/shows/" + show, null, null);
        Map<?, ?> c = (Map<?, ?>) r.body.get("counts");
        int sum = ((Number) c.get("available")).intValue() + ((Number) c.get("held")).intValue()
                + ((Number) c.get("confirmed")).intValue();
        assertThat(sum).isEqualTo(((Number) r.body.get("total_seats")).intValue());
    }

    private static void assertNo5xx(List<Resp> rs) {
        assertThat(rs).noneMatch(r -> r.status >= 500);
    }

    private static long count(List<Resp> rs, int status) {
        return rs.stream().filter(r -> r.status == status).count();
    }

    private static long count(List<Resp> rs, int status, String error) {
        return rs.stream().filter(r -> r.status == status && error.equals(r.body.get("error"))).count();
    }

    private String seatStatus(String show, String seat) {
        return jdbc.queryForObject("SELECT status FROM seats WHERE show_id = ?::uuid AND seat_no = ?", String.class, show, seat);
    }

    private String seatOwner(String show, String seat) {
        return jdbc.queryForObject("SELECT user_id FROM seats WHERE show_id = ?::uuid AND seat_no = ?", String.class, show, seat);
    }

    private String seatReservation(String show, String seat) {
        return jdbc.queryForObject("SELECT reservation_id::text FROM seats WHERE show_id = ?::uuid AND seat_no = ?",
                String.class, show, seat);
    }

    private int heldCount(String show, String user) {
        List<Integer> held = jdbc.queryForList("SELECT held FROM user_show_counts WHERE show_id = ?::uuid AND user_id = ?",
                Integer.class, show, user);
        return held.isEmpty() ? 0 : held.get(0);
    }

    private int confirmedSeatsOf(String show, String user) {
        return jdbc.queryForObject("SELECT count(*) FROM seats WHERE show_id = ?::uuid AND user_id = ? AND status = 'confirmed'",
                Integer.class, show, user);
    }

    private double counter(String name, String reason) {
        var search = meters.find(name);
        if (reason != null) search = search.tag("reason", reason);
        var c = search.counter();
        return c == null ? 0 : c.count();
    }

    private String token(String user) {
        return tokens.issue(user);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static List<String> seats(int n) {
        return IntStream.rangeClosed(1, n).mapToObj(i -> "A" + i).collect(Collectors.toList());
    }

    private static String jsonArray(List<String> items) {
        return items.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(",", "[", "]"));
    }
}
