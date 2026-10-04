package com.divya.seatReservation.dto;

import java.util.List;

// Boxed Long/Integer so a missing field arrives as null and can be rejected
public record CreateShowRequest(String name, List<String> seats, Long pricePaise, Integer perUserLimit) {
}