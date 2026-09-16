package com.yourname.inventory.common.exception;

import com.yourname.inventory.common.lock.LockAcquisitionException;
import com.yourname.inventory.supplier.SupplierUnavailableException;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    public record ErrorResponse(String code, String message, LocalDateTime timestamp) {}

    @ExceptionHandler(EntityNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(EntityNotFoundException ex) {
        return new ErrorResponse("NOT_FOUND", ex.getMessage(), LocalDateTime.now());
    }

    @ExceptionHandler(DuplicateSkuException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleDuplicateSku(DuplicateSkuException ex) {
        return new ErrorResponse("DUPLICATE_SKU", ex.getMessage(), LocalDateTime.now());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ErrorResponse handleIllegalState(IllegalStateException ex) {
        return new ErrorResponse("BUSINESS_RULE_VIOLATION", ex.getMessage(), LocalDateTime.now());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors()
                .stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return new ErrorResponse("VALIDATION_ERROR", message, LocalDateTime.now());
    }

    /*
     * ------------------------------------------------------------------
     * Concurrency failures. All of them are "try again", not "you broke
     * something", so they map to 409 CONFLICT and never to 500.
     * A 500 tells the client "my fault, do not retry"; a 409 with a clear
     * code tells it "transient, retry is safe". That distinction is what
     * lets a well behaved client (or an @Retryable caller upstream) do the
     * right thing automatically.
     * ------------------------------------------------------------------
     */

    /** Somebody else holds the distributed lock and we did not wait long enough. */
    @ExceptionHandler(LockAcquisitionException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleLockBusy(LockAcquisitionException ex) {
        return new ErrorResponse("LOCK_BUSY", ex.getMessage(), LocalDateTime.now());
    }

    /**
     * @Version mismatch that survived every retry. Reaching this handler means the row is so hot
     * that optimistic locking is the wrong tool - switch that path to a pessimistic or
     * distributed lock.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleOptimisticLock(OptimisticLockingFailureException ex) {
        return new ErrorResponse("CONCURRENT_MODIFICATION",
                "The record was modified by another transaction. Please retry.", LocalDateTime.now());
    }

    /** Database row lock timeout (our PESSIMISTIC_WRITE hint is 5s). */
    @ExceptionHandler(CannotAcquireLockException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleRowLockTimeout(CannotAcquireLockException ex) {
        return new ErrorResponse("ROW_LOCK_TIMEOUT",
                "Timed out waiting for a database row lock. Please retry.", LocalDateTime.now());
    }

    /**
     * Retries exhausted against the supplier. 503 + Retry-After is the honest answer for a
     * dependency outage; see RestockService for the alternative, degrading gracefully instead.
     */
    @ExceptionHandler(SupplierUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ErrorResponse handleSupplierDown(SupplierUnavailableException ex) {
        return new ErrorResponse("SUPPLIER_UNAVAILABLE", ex.getMessage(), LocalDateTime.now());
    }
}
