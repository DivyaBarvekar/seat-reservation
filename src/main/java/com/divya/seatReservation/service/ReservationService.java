package com.divya.seatReservation.service;

import com.divya.seatReservation.dto.ReservationResponse;
import com.divya.seatReservation.exception.ApiException;
import com.divya.seatReservation.model.Show;
import com.divya.seatReservation.repository.ReservationRepository;
import com.divya.seatReservation.repository.ReservationRepository.IdempotencyRecord;
import com.divya.seatReservation.repository.ShowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Reservation flow. Everything runs in ONE transaction (READ COMMITTED), and locks are
 * always taken in the same order, so concurrent requests cannot deadlock:
 *
 *   1. idempotency_keys row  (user-scoped)
 *   2. user_show_counts row  (user-scoped)
 *   3. seats rows, one by one in sorted seat_no order
 *
 * Partial requests are ALL-OR-NOTHING: if any requested seat cannot be claimed, the
 * whole transaction rolls back (including the idempotency key and the per-user count),
 * so nothing is held and the client may retry with the same key.
 */
@Service
public class ReservationService {

    public record Result(ReservationResponse reservation, boolean replayed) {}

    private static final Pattern VALID_SEAT = Pattern.compile("[A-Za-z0-9_-]{1,16}");
    private static final int MAX_KEY_LENGTH = 128;
    private static final int MAX_SEATS_PER_REQUEST = 50;

    private final ShowRepository shows;
    private final ReservationRepository repo;

    public ReservationService(ShowRepository shows, ReservationRepository repo) {
        this.shows = shows;
        this.repo = repo;
    }

    @Transactional
    public Result reserve(UUID showId, String userId, List<String> requestedSeats, String idemKey) {
        validateKey(idemKey);
        List<String> seats = normalise(requestedSeats);   // sorted => deterministic lock order
        Show show = shows.findShow(showId).orElseThrow(() -> ApiException.notFound("show not found"));
        String hash = requestHash(showId, seats);

        // 1. Idempotency: first writer of (user, key) wins; everyone else replays or is rejected.
        if (!repo.claimIdempotencyKey(userId, idemKey, showId, hash)) {
            return new Result(replay(userId, idemKey, hash), true);
        }

        if (repo.countExistingSeats(showId, seats) != seats.size()) {
            throw ApiException.badRequest("one or more seats do not exist for this show");
        }

        // 2. Per-user limit, enforced atomically in the database.
        if (seats.size() > show.perUserLimit()
                || !repo.incrementUserCount(showId, userId, seats.size(), show.perUserLimit())) {
            throw ApiException.conflict("per_user_limit",
                    "would exceed the per-user limit of " + show.perUserLimit() + " seats for this show");
        }

        // 3. Claim seats in sorted order; any miss aborts the whole transaction.
        UUID reservationId = UUID.randomUUID();
        long amount = Math.multiplyExact(show.pricePaise(), (long) seats.size());
        repo.insertReservation(reservationId, showId, userId, seats, amount);
        for (String seat : seats) {
            if (!repo.claimSeat(showId, seat, reservationId, userId)) {
                throw ApiException.conflict("seat_taken", "seat " + seat + " is not available");
            }
        }
        repo.linkIdempotencyKey(userId, idemKey, reservationId);

        return new Result(new ReservationResponse(reservationId, showId, userId, seats, amount, "confirmed"), false);
    }

    /**
     * Owner-only cancel. Lock order matches reserve() for the parts they share
     * (user_show_counts before seats), so a cancel and a reserve by the same user
     * cannot deadlock. Cancelling an already-cancelled reservation is a no-op.
     */
    @Transactional
    public ReservationResponse cancel(UUID reservationId, String userId) {
        // 1. Lock the reservation row: two concurrent cancels of the same reservation serialise here.
        ReservationResponse r = repo.findReservationForUpdate(reservationId)
                .filter(res -> res.userId().equals(userId))  // someone else's => 404, don't reveal it exists
                .orElseThrow(() -> ApiException.notFound("reservation not found"));

        if (r.status().equals("cancelled")) {
            return r;
        }

        // 2. Per-user count first (same order as reserve), then 3. the seats.
        repo.decrementUserCount(r.showId(), userId, r.seats().size());
        int released = repo.releaseSeats(r.showId(), reservationId);
        if (released != r.seats().size()) {
            // Would mean seat rows and the reservation disagree; roll back rather than corrupt counts.
            throw new IllegalStateException("reservation " + reservationId + " owns " + released
                    + " seats, expected " + r.seats().size());
        }
        repo.markCancelled(reservationId);

        return new ReservationResponse(r.reservationId(), r.showId(), r.userId(), r.seats(), r.amountPaise(), "cancelled");
    }

    private ReservationResponse replay(String userId, String idemKey, String hash) {
        IdempotencyRecord rec = repo.findIdempotencyKey(userId, idemKey)
                .orElseThrow(() -> new IllegalStateException("idempotency key vanished after conflict"));
        if (!rec.requestHash().equals(hash)) {
            throw ApiException.conflict("idempotency_key_reused",
                    "idempotency key was already used with a different request");
        }
        // reservation_id is set in the same transaction that inserted the key, so a
        // committed key always points at its reservation.
        return repo.findReservation(rec.reservationId())
                .orElseThrow(() -> new IllegalStateException("idempotency key points at missing reservation"));
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank())
            throw ApiException.badRequest("idempotency key is required (Idempotency-Key header or idempotency_key field)");
        if (key.length() > MAX_KEY_LENGTH)
            throw ApiException.badRequest("idempotency key too long (max " + MAX_KEY_LENGTH + ")");
    }

    private static List<String> normalise(List<String> seats) {
        if (seats == null || seats.isEmpty())
            throw ApiException.badRequest("seats must be a non-empty list");
        if (seats.size() > MAX_SEATS_PER_REQUEST)
            throw ApiException.badRequest("too many seats in one request");
        for (String s : seats) {
            if (s == null || !VALID_SEAT.matcher(s).matches())
                throw ApiException.badRequest("invalid seat label: " + s);
        }
        if (new HashSet<>(seats).size() != seats.size())
            throw ApiException.badRequest("duplicate seat labels");
        return seats.stream().sorted().toList();
    }

    /** Canonical fingerprint of the request body: same show + same set of seats => same hash. */
    private static String requestHash(UUID showId, List<String> sortedSeats) {
        try {
            String canonical = showId + "|" + String.join(",", sortedSeats);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
