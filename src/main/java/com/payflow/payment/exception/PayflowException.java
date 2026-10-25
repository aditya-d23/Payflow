package com.payflow.payment.exception;

/**
 * Base class for expected business failures. The message must be safe to
 * return to the caller.
 */
public class PayflowException extends RuntimeException {

    private final ErrorCode errorCode;

    public PayflowException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
