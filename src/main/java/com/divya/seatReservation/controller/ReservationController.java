package com.divya.seatReservation.controller;

import com.divya.seatReservation.dto.ReservationResponse;
import com.divya.seatReservation.dto.ReserveRequest;
import com.divya.seatReservation.exception.ApiException;
import com.divya.seatReservation.filter.AuthFilter;
import com.divya.seatReservation.service.ReservationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class ReservationController {

    private final ReservationService service;

    public ReservationController(ReservationService service) {
        this.service = service;
    }

    @PostMapping("/shows/{id}/reserve")
    public ResponseEntity<ReservationResponse> reserve(
            @PathVariable UUID id,
            @RequestAttribute(AuthFilter.USER_ATTR) String userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String headerKey,
            @RequestBody ReserveRequest req) {

        String bodyKey = req.idempotencyKey();
        if (headerKey != null && bodyKey != null && !headerKey.equals(bodyKey)) {
            throw ApiException.badRequest("Idempotency-Key header and idempotency_key field differ");
        }
        String key = headerKey != null ? headerKey : bodyKey;

        ReservationService.Result result = service.reserve(id, userId, req.seats(), key);
        // 201 for the reservation that was created; 200 when a retry replays the original.
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(result.reservation());
    }

    @PostMapping("/reservations/{id}/cancel")
    public ReservationResponse cancel(@PathVariable UUID id,
                                      @RequestAttribute(AuthFilter.USER_ATTR) String userId) {
        return service.cancel(id, userId);
    }
}
