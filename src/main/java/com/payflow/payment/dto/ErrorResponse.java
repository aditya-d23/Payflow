package com.payflow.payment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorResponse(
        OffsetDateTime timestamp,
        int status,
        String code,
        String message,
        String path,
        String correlationId,
        List<FieldError> fieldErrors
) {
    public record FieldError(String field, String message) {}
}
