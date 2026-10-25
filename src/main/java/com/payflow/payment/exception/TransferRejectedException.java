package com.payflow.payment.exception;

/**
 * A transfer that is well-formed but violates a business rule, such as a
 * same-account transfer, a currency mismatch, or an inactive account.
 */
public class TransferRejectedException extends PayflowException {

    public TransferRejectedException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
