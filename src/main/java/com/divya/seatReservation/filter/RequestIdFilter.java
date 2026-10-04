package com.divya.seatReservation.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Runs before everything else (including AuthFilter). Gives every request a correlation id:
 * the caller's X-Request-Id if it's sane, otherwise a fresh UUID. The id goes into the MDC
 * (so every log line written while handling the request carries it) and back out on the
 * response, and one access-log line is written per request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "request_id";

    private static final Logger log = LoggerFactory.getLogger("access");
    // Caller-supplied ids end up in our logs, so only accept short, boring ones.
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        String given = req.getHeader(HEADER);
        String requestId = given != null && SAFE_ID.matcher(given).matches() ? given : UUID.randomUUID().toString();

        MDC.put(MDC_REQUEST_ID, requestId);
        res.setHeader(HEADER, requestId);
        long start = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            log.atInfo()
                    .addKeyValue("method", req.getMethod())
                    .addKeyValue("path", req.getRequestURI())
                    .addKeyValue("status", res.getStatus())
                    .addKeyValue("duration_ms", (System.nanoTime() - start) / 1_000_000)
                    .log("request completed");
            MDC.clear();  // Tomcat reuses threads; don't leak this request's ids into the next
        }
    }
}
