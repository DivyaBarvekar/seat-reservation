package com.divya.seatReservation.filter;

import com.divya.seatReservation.service.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

@Component
public class AuthFilter extends OncePerRequestFilter {

    /** Request attribute holding the verified user id. The ONLY source of identity. */
    public static final String USER_ATTR = "authUserId";

    private final TokenService tokens;
    private final byte[] adminToken;

    public AuthFilter(TokenService tokens, @Value("${app.auth.admin-token}") String adminToken) {
        this.tokens = tokens;
        this.adminToken = adminToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        String method = req.getMethod();
        String path = req.getRequestURI();

        // Rule 1: public reads and token minting
        if (method.equals("GET") || (method.equals("POST") && path.equals("/auth/token"))) {
            chain.doFilter(req, res);
            return;
        }

        // Rule 2: admin-only show creation
        if (method.equals("POST") && path.equals("/shows")) {
            String given = req.getHeader("X-Admin-Token");
            if (given == null || !MessageDigest.isEqual(adminToken, given.getBytes(StandardCharsets.UTF_8))) {
                reject(res, "admin token required");
                return;
            }
            chain.doFilter(req, res);
            return;
        }

        // Rule 3 (default-deny): everything else needs a valid user token
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            reject(res, "missing bearer token");
            return;
        }
        Optional<String> user = tokens.verify(header.substring(7).trim());
        if (user.isEmpty()) {
            reject(res, "invalid or expired token");
            return;
        }
        req.setAttribute(USER_ATTR, user.get());
        MDC.put("user_id", user.get());  // cleared by RequestIdFilter at the end of the request
        chain.doFilter(req, res);
    }

    private void reject(HttpServletResponse res, String message) throws IOException {
        res.setStatus(401);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"" + message + "\"}");
    }
}
