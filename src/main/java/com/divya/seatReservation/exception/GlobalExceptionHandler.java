package com.divya.seatReservation.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ErrorBody(String error, String message) {}

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorBody> handleApi(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(new ErrorBody(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> handleBadJson(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorBody("malformed_json", "request body is missing or not valid JSON"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorBody> handleBadParam(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorBody("invalid_parameter", e.getName() + " has an invalid format"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> handleOther(Exception e) {
        // Keep Spring's own statuses (404 unknown URL, 405 wrong method) instead of turning them into 500
        if (e instanceof ErrorResponse er) {
            return ResponseEntity.status(er.getStatusCode()).body(new ErrorBody("http_error", e.getMessage()));
        }
        log.error("Unexpected error", e);
        return ResponseEntity.internalServerError().body(new ErrorBody("internal_error", "unexpected error"));
    }
}