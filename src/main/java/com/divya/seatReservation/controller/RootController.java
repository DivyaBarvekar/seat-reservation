package com.divya.seatReservation.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Landing response for the bare service URL: what this is and where everything lives. */
@RestController
public class RootController {

    private static final Map<String, Object> INDEX = buildIndex();

    @GetMapping("/")
    public Map<String, Object> index() {
        return INDEX;
    }

    private static Map<String, Object> buildIndex() {
        Map<String, String> endpoints = new LinkedHashMap<>();
        endpoints.put("POST /auth/token", "get a user token: {\"user\":\"alice\"} -> use as 'Authorization: Bearer <token>'");
        endpoints.put("POST /shows", "create a show (admin, X-Admin-Token header)");
        endpoints.put("GET /shows/{id}", "per-seat status and counts");
        endpoints.put("POST /shows/{id}/reserve", "reserve seats (Bearer token + idempotency key)");
        endpoints.put("POST /reservations/{id}/cancel", "cancel your own reservation");
        endpoints.put("GET /actuator/health/liveness", "liveness");
        endpoints.put("GET /actuator/health/readiness", "readiness (checks the database)");
        endpoints.put("GET /actuator/prometheus", "metrics");

        Map<String, Object> index = new LinkedHashMap<>();
        index.put("service", "seat-reservation");
        index.put("description", "Seat reservation API: no seat is ever sold twice, even under an on-sale stampede");
        index.put("endpoints", endpoints);
        index.put("source", "https://github.com/DivyaBarvekar/seat-reservation");
        return index;
    }
}
