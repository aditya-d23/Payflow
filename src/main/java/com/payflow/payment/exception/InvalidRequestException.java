package com.payflow.payment.exception;

/**
 * Input that passed bean validation but is still unacceptable, for example an
 * amount with more decimal places than its currency allows.
 */
public class InvalidRequestException extends PayflowException {

    public InvalidRequestException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
