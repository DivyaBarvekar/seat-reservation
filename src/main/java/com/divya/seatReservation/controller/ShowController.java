package com.divya.seatReservation.controller;

import com.divya.seatReservation.dto.CreateShowRequest;
import com.divya.seatReservation.dto.ShowResponse;
import com.divya.seatReservation.service.ShowService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/shows")
public class ShowController {

    private final ShowService service;

    public ShowController(ShowService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ShowResponse> create(@RequestBody CreateShowRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping("/{id}")
    public ShowResponse get(@PathVariable UUID id) {
        return service.get(id);
    }
}