package com.divya.seatReservation.model;

import java.util.UUID;

public record Show(UUID id, String name, long pricePaise, int perUserLimit, int totalSeats) {
}