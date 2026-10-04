package com.divya.seatReservation.repository;

import com.divya.seatReservation.dto.ReservationResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReservationRepository {

    public record IdempotencyRecord(UUID showId, String requestHash, UUID reservationId) {}

    private final JdbcTemplate jdbc;

    public ReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims (user_id, idem_key) for this transaction. Returns false if the key already exists.
     * If another in-flight transaction holds the same key, Postgres blocks here until it
     * commits (we get false) or rolls back (we get the key) — so two concurrent first
     * attempts with the same key can never both proceed.
     */
    public boolean claimIdempotencyKey(String userId, String key, UUID showId, String requestHash) {
        return jdbc.update("""
                INSERT INTO idempotency_keys (user_id, idem_key, show_id, request_hash)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id, idem_key) DO NOTHING
                """, userId, key, showId, requestHash) == 1;
    }

    public Optional<IdempotencyRecord> findIdempotencyKey(String userId, String key) {
        return jdbc.query(
                "SELECT show_id, request_hash, reservation_id FROM idempotency_keys WHERE user_id = ? AND idem_key = ?",
                (rs, i) -> new IdempotencyRecord(
                        rs.getObject("show_id", UUID.class),
                        rs.getString("request_hash"),
                        rs.getObject("reservation_id", UUID.class)),
                userId, key).stream().findFirst();
    }

    public void linkIdempotencyKey(String userId, String key, UUID reservationId) {
        jdbc.update("UPDATE idempotency_keys SET reservation_id = ? WHERE user_id = ? AND idem_key = ?",
                reservationId, userId, key);
    }

    /**
     * Atomically adds n to the user's seat count for this show, but only if the result stays
     * within the limit. The row lock also serialises concurrent reserves by the same user
     * for the same show. Returns false when the limit would be exceeded.
     */
    public boolean incrementUserCount(UUID showId, String userId, int n, int limit) {
        return jdbc.update("""
                INSERT INTO user_show_counts (show_id, user_id, held) VALUES (?, ?, ?)
                ON CONFLICT (show_id, user_id)
                DO UPDATE SET held = user_show_counts.held + EXCLUDED.held
                WHERE user_show_counts.held + EXCLUDED.held <= ?
                """, showId, userId, n, limit) == 1;
    }

    /**
     * Current status of each requested seat that exists (seats are never deleted), as a plain
     * non-locking read. Used only to decline early — it never grants a seat; claimSeat does that.
     */
    public Map<String, String> findSeatStatuses(UUID showId, List<String> seats) {
        Map<String, String> statuses = new HashMap<>();
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "SELECT seat_no, status FROM seats WHERE show_id = ? AND seat_no = ANY(?)");
            ps.setObject(1, showId);
            ps.setArray(2, con.createArrayOf("text", seats.toArray()));
            return ps;
        }, rs -> {
            statuses.put(rs.getString("seat_no"), rs.getString("status"));
        });
        return statuses;
    }

    public void insertReservation(UUID id, UUID showId, String userId, List<String> seats, long amountPaise) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO reservations (id, show_id, user_id, seats, amount_paise, status)
                    VALUES (?, ?, ?, ?, ?, 'confirmed')
                    """);
            ps.setObject(1, id);
            ps.setObject(2, showId);
            ps.setString(3, userId);
            ps.setArray(4, con.createArrayOf("text", seats.toArray()));
            ps.setLong(5, amountPaise);
            return ps;
        });
    }

    /**
     * THE atomic decision. A conditional update guarded on current state: only an
     * 'available' row can flip to 'confirmed'. Concurrent claimers of the same seat
     * queue on the row lock; when the winner commits, Postgres re-checks the WHERE
     * clause against the new row version, finds status='confirmed', and updates 0 rows.
     */
    public boolean claimSeat(UUID showId, String seat, UUID reservationId, String userId) {
        return jdbc.update("""
                UPDATE seats SET status = 'confirmed', reservation_id = ?, user_id = ?
                WHERE show_id = ? AND seat_no = ? AND status = 'available'
                """, reservationId, userId, showId, seat) == 1;
    }

    /**
     * Returns seats to 'available', but ONLY those still owned by this reservation.
     * A seat that has since been re-booked by someone else points at a different
     * reservation_id and is untouched — so a release can never resurrect or steal it.
     */
    public int releaseSeats(UUID showId, UUID reservationId) {
        return jdbc.update("""
                UPDATE seats SET status = 'available', reservation_id = NULL, user_id = NULL
                WHERE show_id = ? AND reservation_id = ? AND status = 'confirmed'
                """, showId, reservationId);
    }

    public void decrementUserCount(UUID showId, String userId, int n) {
        jdbc.update("UPDATE user_show_counts SET held = held - ? WHERE show_id = ? AND user_id = ?",
                n, showId, userId);
    }

    public void markCancelled(UUID reservationId) {
        jdbc.update("UPDATE reservations SET status = 'cancelled' WHERE id = ?", reservationId);
    }

    /** Same as findReservation, but row-locks the reservation so concurrent cancels serialise. */
    public Optional<ReservationResponse> findReservationForUpdate(UUID id) {
        return queryReservation("SELECT id, show_id, user_id, seats, amount_paise, status FROM reservations WHERE id = ? FOR UPDATE", id);
    }

    public Optional<ReservationResponse> findReservation(UUID id) {
        return queryReservation("SELECT id, show_id, user_id, seats, amount_paise, status FROM reservations WHERE id = ?", id);
    }

    private Optional<ReservationResponse> queryReservation(String sql, UUID id) {
        return jdbc.query(
                sql,
                (rs, i) -> {
                    Array arr = rs.getArray("seats");
                    List<String> seats = Arrays.asList((String[]) arr.getArray());
                    return new ReservationResponse(
                            rs.getObject("id", UUID.class),
                            rs.getObject("show_id", UUID.class),
                            rs.getString("user_id"),
                            seats,
                            rs.getLong("amount_paise"),
                            rs.getString("status"));
                },
                id).stream().findFirst();
    }
}
