package com.payflow.payment.exception;

public class TransferNotFoundException extends PayflowException {

    public TransferNotFoundException(String message) {
        super(ErrorCode.TRANSFER_NOT_FOUND, message);
    }
}
