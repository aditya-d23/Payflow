package com.payflow.payment.exception;

import org.springframework.http.HttpStatus;

/**
 * Stable, client-facing error codes. Clients should branch on the code, never
 * on the human-readable message.
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
    INVALID_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST),
    UNSUPPORTED_CURRENCY(HttpStatus.BAD_REQUEST),
    INVALID_AMOUNT(HttpStatus.BAD_REQUEST),

    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(HttpStatus.FORBIDDEN),

    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND),
    TRANSFER_NOT_FOUND(HttpStatus.NOT_FOUND),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),

    ACCOUNT_ALREADY_EXISTS(HttpStatus.CONFLICT),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT),
    DATA_CONFLICT(HttpStatus.CONFLICT),

    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_CONTENT),
    SAME_ACCOUNT_TRANSFER(HttpStatus.UNPROCESSABLE_CONTENT),
    CURRENCY_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT),
    ACCOUNT_NOT_ACTIVE(HttpStatus.UNPROCESSABLE_CONTENT),
    INVALID_ACCOUNT_STATE(HttpStatus.UNPROCESSABLE_CONTENT),

    LOCK_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    static ErrorCode forFrameworkStatus(int status) {
        return switch (status) {
            case 404 -> RESOURCE_NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            default -> status >= 500 ? INTERNAL_ERROR : MALFORMED_REQUEST;
        };
    }
}
