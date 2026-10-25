package com.payflow.payment.exception;

public class AccountAlreadyExistsException extends PayflowException {

    public AccountAlreadyExistsException(String message) {
        super(ErrorCode.ACCOUNT_ALREADY_EXISTS, message);
    }
}
