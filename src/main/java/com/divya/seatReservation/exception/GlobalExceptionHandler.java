package com.divya.seatReservation.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
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

    // Deadlock / lock timeout. Shouldn't happen given our lock ordering, but if it does the
    // transaction was rolled back and nothing was reserved: a clean, retryable decline.
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ErrorBody> handleLockFailure(PessimisticLockingFailureException e) {
        log.warn("Lock conflict, request declined: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorBody("contention", "request conflicted with another; please retry"));
    }

    // DB unreachable, connection lost mid-request, or pool exhausted: fail closed with a
    // retryable 503. If the connection dropped during COMMIT the outcome is unknown to us,
    // which is exactly what the idempotency key is for: a retry with the same key either
    // replays the reservation that did commit or makes it now.
    //   CannotCreateTransactionException   - @Transactional couldn't get a connection
    //   DataAccessResourceFailureException - connection refused/killed (incl. CannotGetJdbcConnectionException)
    //   TransientDataAccessResourceException, RecoverableDataAccessException - transient connection errors
    //   TransactionSystemException         - commit/rollback failed on a dead connection
    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class,
            TransientDataAccessResourceException.class, RecoverableDataAccessException.class,
            TransactionSystemException.class})
    public ResponseEntity<ErrorBody> handleDbUnavailable(Exception e) {
        log.warn("Database unavailable, request rejected: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(new ErrorBody("unavailable", "service temporarily unavailable; please retry"));
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