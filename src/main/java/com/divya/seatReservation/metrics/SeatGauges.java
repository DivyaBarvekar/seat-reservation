package com.divya.seatReservation.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-show seat gauges read straight from the seats table — the same source GET /shows/{id}
 * uses — so they reconcile with the API by construction. All four numbers for a show come
 * from ONE query (one snapshot), so available + held + confirmed == total in the metrics too.
 */
@Component
public class SeatGauges {

    private static final Logger log = LoggerFactory.getLogger(SeatGauges.class);

    private final JdbcTemplate jdbc;
    private final MultiGauge available;
    private final MultiGauge held;
    private final MultiGauge confirmed;
    private final MultiGauge total;

    public SeatGauges(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.available = MultiGauge.builder("seats.available").description("Seats currently available").register(registry);
        this.held = MultiGauge.builder("seats.held").description("Seats currently held").register(registry);
        this.confirmed = MultiGauge.builder("seats.confirmed").description("Seats currently confirmed").register(registry);
        this.total = MultiGauge.builder("seats.capacity").description("Total seats (not seats_total: Prometheus reserves _total for counters)").register(registry);
    }

    @Scheduled(fixedDelayString = "${app.metrics.seat-gauge-refresh-ms:5000}")
    public void refresh() {
        try {
            List<MultiGauge.Row<?>> a = new ArrayList<>(), h = new ArrayList<>(), c = new ArrayList<>(), t = new ArrayList<>();
            jdbc.query("""
                    SELECT show_id,
                           count(*) FILTER (WHERE status = 'available') AS available,
                           count(*) FILTER (WHERE status = 'held')      AS held,
                           count(*) FILTER (WHERE status = 'confirmed') AS confirmed,
                           count(*)                                     AS total
                    FROM seats GROUP BY show_id
                    """, rs -> {
                Tags tags = Tags.of("show_id", rs.getString("show_id"));
                a.add(MultiGauge.Row.of(tags, rs.getLong("available")));
                h.add(MultiGauge.Row.of(tags, rs.getLong("held")));
                c.add(MultiGauge.Row.of(tags, rs.getLong("confirmed")));
                t.add(MultiGauge.Row.of(tags, rs.getLong("total")));
            });
            available.register(a, true);
            held.register(h, true);
            confirmed.register(c, true);
            total.register(t, true);
        } catch (RuntimeException e) {
            // DB down: keep the last values rather than crash the scheduler; readiness reports the outage.
            log.warn("Seat gauge refresh failed: {}", e.getMessage());
        }
    }
}
