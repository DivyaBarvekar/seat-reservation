package com.divya.seatReservation.controller;

import com.divya.seatReservation.dto.TokenRequest;
import com.divya.seatReservation.dto.TokenResponse;
import com.divya.seatReservation.service.TokenService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.regex.Pattern;

@RestController
public class AuthController {

    private static final Pattern VALID_USER = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final TokenService tokens;

    public AuthController(TokenService tokens) {
        this.tokens = tokens;
    }

    @PostMapping("/auth/token")
    public ResponseEntity<?> token(@RequestBody TokenRequest req) {
        if (req.user() == null || !VALID_USER.matcher(req.user()).matches()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "invalid_user", "message", "user must match [A-Za-z0-9_-]{1,64}"));
        }
        return ResponseEntity.ok(new TokenResponse(tokens.issue(req.user()), req.user()));
    }
}
