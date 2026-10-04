package com.divya.seatReservation.controller;

import com.divya.seatReservation.dto.ReservationResponse;
import com.divya.seatReservation.dto.ReserveRequest;
import com.divya.seatReservation.exception.ApiException;
import com.divya.seatReservation.filter.AuthFilter;
import com.divya.seatReservation.metrics.ReservationMetrics;
import com.divya.seatReservation.service.ReservationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class ReservationController {

    private static final Logger log = LoggerFactory.getLogger(ReservationController.class);

    private final ReservationService service;
    private final ReservationMetrics metrics;

    public ReservationController(ReservationService service, ReservationMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
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

        // Metrics and outcome logs are recorded here, after service.reserve() returns, because by
        // then its transaction has committed (or rolled back) — we never report a phantom.
        ReservationService.Result result;
        try {
            result = service.reserve(id, userId, req.seats(), key);
        } catch (ApiException e) {
            metrics.declined(e.getCode());
            log.atInfo().addKeyValue("show_id", id).addKeyValue("seats", req.seats())
                    .addKeyValue("reason", e.getCode()).log("reservation declined");
            throw e;
        } catch (PessimisticLockingFailureException e) {
            metrics.declined("contention");
            log.atWarn().addKeyValue("show_id", id).addKeyValue("seats", req.seats())
                    .addKeyValue("reason", "contention").log("reservation declined");
            throw e;
        }

        ReservationResponse r = result.reservation();
        if (result.replayed()) {
            metrics.replayed();
            log.atInfo().addKeyValue("show_id", id).addKeyValue("reservation_id", r.reservationId())
                    .log("reservation replayed for idempotent retry");
        } else {
            metrics.confirmed(r.seats().size());
            log.atInfo().addKeyValue("show_id", id).addKeyValue("reservation_id", r.reservationId())
                    .addKeyValue("seats", r.seats()).addKeyValue("amount_paise", r.amountPaise())
                    .log("reservation confirmed");
        }
        // 201 for the reservation that was created; 200 when a retry replays the original.
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(result.reservation());
    }

    @PostMapping("/reservations/{id}/cancel")
    public ReservationResponse cancel(@PathVariable UUID id,
                                      @RequestAttribute(AuthFilter.USER_ATTR) String userId) {
        ReservationService.Result result = service.cancel(id, userId);
        ReservationResponse r = result.reservation();
        if (!result.replayed()) {
            metrics.cancelled(r.seats().size());
            log.atInfo().addKeyValue("show_id", r.showId()).addKeyValue("reservation_id", r.reservationId())
                    .addKeyValue("seats", r.seats()).log("reservation cancelled");
        }
        return r;
    }
}
