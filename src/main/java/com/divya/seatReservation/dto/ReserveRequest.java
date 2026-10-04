package com.divya.seatReservation.dto;

import java.util.List;

// Deliberately no user_id field: identity comes only from the bearer token.
// Unknown fields (e.g. a spoofed "user_id") are ignored by Jackson.
public record ReserveRequest(List<String> seats, String idempotencyKey) {
}
