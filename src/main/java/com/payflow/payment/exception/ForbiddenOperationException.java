package com.payflow.payment.exception;

public class ForbiddenOperationException extends PayflowException {

    public ForbiddenOperationException(String message) {
        super(ErrorCode.ACCESS_DENIED, message);
    }
}
