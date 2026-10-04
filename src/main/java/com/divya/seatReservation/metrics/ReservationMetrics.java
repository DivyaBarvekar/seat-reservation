package com.divya.seatReservation.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Outcome counters. Callers record only AFTER the transaction has committed (or definitely
 * rolled back), so the counters can't count a reservation that never actually happened.
 *
 * No show_id tag on counters: a request for a random show id would otherwise mint a new
 * time series per id. Per-show state lives on the DB-backed gauges (SeatGauges) instead.
 *
 * Reconciliation (over any window, e.g. before vs after a burst):
 *   delta(reservation_seats_confirmed_total) - delta(reservation_seats_released_total)
 *     == delta(seats_confirmed gauge)
 */
@Component
public class ReservationMetrics {

    /** Decline reasons we report as-is; any other client error is lumped into "invalid_request". */
    private static final Set<String> KNOWN_REASONS =
            Set.of("seat_taken", "per_user_limit", "idempotency_key_reused", "contention");

    private final MeterRegistry registry;
    private final Counter confirmed;
    private final Counter seatsConfirmed;
    private final Counter cancelled;
    private final Counter seatsReleased;

    public ReservationMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.confirmed = Counter.builder("reservations.confirmed")
                .description("Reservations created (one per successful 201)").register(registry);
        this.seatsConfirmed = Counter.builder("reservation.seats.confirmed")
                .description("Seats confirmed by new reservations").register(registry);
        this.cancelled = Counter.builder("reservations.cancelled")
                .description("Reservations cancelled (repeat cancels not counted)").register(registry);
        this.seatsReleased = Counter.builder("reservation.seats.released")
                .description("Seats returned to available by cancels").register(registry);
        // Pre-register every reason so they all show up at 0 before the first burst.
        for (String r : KNOWN_REASONS) declinedCounter(r);
        declinedCounter("idempotent_replay");
        declinedCounter("invalid_request");
    }

    public void confirmed(int seats) {
        confirmed.increment();
        seatsConfirmed.increment(seats);
    }

    /** A retry with an already-used key: returned the original, moved nothing. */
    public void replayed() {
        declinedCounter("idempotent_replay").increment();
    }

    public void declined(String code) {
        declinedCounter(KNOWN_REASONS.contains(code) ? code : "invalid_request").increment();
    }

    public void cancelled(int seats) {
        cancelled.increment();
        seatsReleased.increment(seats);
    }

    private Counter declinedCounter(String reason) {
        // Micrometer returns the existing counter for the same name + tags, so this is cheap.
        return Counter.builder("reservations.declined")
                .description("Reserve requests that did not create a reservation, by reason")
                .tag("reason", reason)
                .register(registry);
    }
}
