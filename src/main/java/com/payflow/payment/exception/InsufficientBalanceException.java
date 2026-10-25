package com.payflow.payment.exception;

public class InsufficientBalanceException extends PayflowException {

    public InsufficientBalanceException(String message) {
        super(ErrorCode.INSUFFICIENT_FUNDS, message);
    }
}
