package com.divya.seatReservation.service;

import com.divya.seatReservation.dto.CreateShowRequest;
import com.divya.seatReservation.dto.SeatCounts;
import com.divya.seatReservation.dto.SeatView;
import com.divya.seatReservation.dto.ShowResponse;
import com.divya.seatReservation.exception.ApiException;
import com.divya.seatReservation.model.Show;
import com.divya.seatReservation.repository.ShowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ShowService {

    private static final Pattern VALID_SEAT = Pattern.compile("[A-Za-z0-9_-]{1,16}");
    private static final int MAX_SEATS = 50_000;
    private static final int DEFAULT_LIMIT = 4;

    private final ShowRepository repo;

    public ShowService(ShowRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public ShowResponse create(CreateShowRequest req) {
        validate(req);
        int limit = req.perUserLimit() == null ? DEFAULT_LIMIT : req.perUserLimit();
        String name = req.name().trim();

        UUID id = repo.insertShow(name, req.pricePaise(), limit, req.seats().size());
        repo.insertSeats(id, req.seats());

        List<SeatView> seats = req.seats().stream().map(s -> new SeatView(s, "available")).toList();
        return new ShowResponse(id, name, req.pricePaise(), limit, seats.size(),
                new SeatCounts(seats.size(), 0, 0), seats);
    }

    public ShowResponse get(UUID id) {
        Show show = repo.findShow(id).orElseThrow(() -> ApiException.notFound("show not found"));
        List<SeatView> seats = repo.findSeats(id);

        // Counts come from the same single-query snapshot we return,
        // so available + held + confirmed == total always holds, even mid-burst.
        int available = 0, held = 0, confirmed = 0;
        for (SeatView s : seats) {
            switch (s.status()) {
                case "available" -> available++;
                case "held" -> held++;
                case "confirmed" -> confirmed++;
                default -> throw new IllegalStateException("unknown seat status: " + s.status());
            }
        }
        return new ShowResponse(show.id(), show.name(), show.pricePaise(), show.perUserLimit(),
                show.totalSeats(), new SeatCounts(available, held, confirmed), seats);
    }

    private void validate(CreateShowRequest req) {
        if (req.name() == null || req.name().isBlank())
            throw ApiException.badRequest("name is required");
        if (req.seats() == null || req.seats().isEmpty())
            throw ApiException.badRequest("seats must be a non-empty list");
        if (req.seats().size() > MAX_SEATS)
            throw ApiException.badRequest("too many seats (max " + MAX_SEATS + ")");
        for (String s : req.seats()) {
            if (s == null || !VALID_SEAT.matcher(s).matches())
                throw ApiException.badRequest("invalid seat label: " + s);
        }
        if (new HashSet<>(req.seats()).size() != req.seats().size())
            throw ApiException.badRequest("duplicate seat labels");
        if (req.pricePaise() == null || req.pricePaise() < 0)
            throw ApiException.badRequest("price_paise must be a non-negative integer");
        if (req.perUserLimit() != null && req.perUserLimit() <= 0)
            throw ApiException.badRequest("per_user_limit must be positive");
    }
}