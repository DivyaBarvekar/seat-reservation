package com.divya.seatReservation.repository;

import com.divya.seatReservation.dto.SeatView;
import com.divya.seatReservation.model.Show;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ShowRepository {

    private final JdbcTemplate jdbc;

    public ShowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insertShow(String name, long pricePaise, int perUserLimit, int totalSeats) {
        return jdbc.queryForObject(
                "INSERT INTO shows (name, price_paise, per_user_limit, total_seats) VALUES (?, ?, ?, ?) RETURNING id",
                UUID.class, name, pricePaise, perUserLimit, totalSeats);
    }

    public void insertSeats(UUID showId, List<String> seats) {
        jdbc.batchUpdate(
                "INSERT INTO seats (show_id, seat_no) VALUES (?, ?)",
                seats, 500,
                (ps, seat) -> {
                    ps.setObject(1, showId);
                    ps.setString(2, seat);
                });
    }

    public Optional<Show> findShow(UUID id) {
        List<Show> rows = jdbc.query(
                "SELECT id, name, price_paise, per_user_limit, total_seats FROM shows WHERE id = ?",
                (rs, i) -> new Show(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getLong("price_paise"),
                        rs.getInt("per_user_limit"),
                        rs.getInt("total_seats")),
                id);
        return rows.stream().findFirst();
    }

    public List<SeatView> findSeats(UUID showId) {
        return jdbc.query(
                "SELECT seat_no, status FROM seats WHERE show_id = ? ORDER BY seat_no",
                (rs, i) -> new SeatView(rs.getString("seat_no"), rs.getString("status")),
                showId);
    }
}