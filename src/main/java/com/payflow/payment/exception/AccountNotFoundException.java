package com.payflow.payment.exception;

public class AccountNotFoundException extends PayflowException {

    public AccountNotFoundException(String message) {
        super(ErrorCode.ACCOUNT_NOT_FOUND, message);
    }
}
