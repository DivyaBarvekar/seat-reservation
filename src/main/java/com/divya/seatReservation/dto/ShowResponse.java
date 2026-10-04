package com.divya.seatReservation.dto;

import java.util.List;
import java.util.UUID;

public record ShowResponse(UUID id, String name, long pricePaise, int perUserLimit,
                           int totalSeats, SeatCounts counts, List<SeatView> seats) {
}