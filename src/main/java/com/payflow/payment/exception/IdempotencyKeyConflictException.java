package com.payflow.payment.exception;

public class IdempotencyKeyConflictException extends PayflowException {

    public IdempotencyKeyConflictException(String message) {
        super(ErrorCode.IDEMPOTENCY_KEY_REUSED, message);
    }
}
