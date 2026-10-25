package com.payflow.payment.exception;

import com.payflow.payment.dto.ErrorResponse;
import com.payflow.payment.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Maps every failure to {@link ErrorResponse}. Messages for unexpected errors
 * are generic: stack traces, SQL, and constraint names never reach the client.
 * Handlers run after the transactional proxy has already rolled back.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(PayflowException.class)
    ResponseEntity<ErrorResponse> handlePayflow(
            PayflowException exception, HttpServletRequest request) {
        return respond(exception.getErrorCode(), exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleInvalidBody(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        List<ErrorResponse.FieldError> fieldErrors = exception.getBindingResult()
                .getFieldErrors().stream()
                .map(error -> new ErrorResponse.FieldError(
                        error.getField(), error.getDefaultMessage()))
                .toList();
        return respond(ErrorCode.VALIDATION_FAILED,
                "Request validation failed", request, fieldErrors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ErrorResponse> handleInvalidParameters(
            HandlerMethodValidationException exception,
            HttpServletRequest request) {
        List<ErrorResponse.FieldError> fieldErrors = exception
                .getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new ErrorResponse.FieldError(
                                result.getMethodParameter().getParameterName(),
                                ((DefaultMessageSourceResolvable) error)
                                        .getDefaultMessage())))
                .toList();
        return respond(ErrorCode.VALIDATION_FAILED,
                "Request validation failed", request, fieldErrors);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ErrorResponse> handleMissingHeader(
            MissingRequestHeaderException exception,
            HttpServletRequest request) {
        ErrorCode code = "Idempotency-Key".equalsIgnoreCase(exception.getHeaderName())
                ? ErrorCode.INVALID_IDEMPOTENCY_KEY
                : ErrorCode.VALIDATION_FAILED;
        return respond(code,
                "Required header '" + exception.getHeaderName() + "' is missing",
                request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleUnreadable(HttpServletRequest request) {
        return respond(ErrorCode.MALFORMED_REQUEST,
                "Request body is missing or malformed", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request) {
        return respond(ErrorCode.MALFORMED_REQUEST,
                "Parameter '" + exception.getName() + "' has an invalid value",
                request);
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ErrorResponse> handleLockFailure(
            PessimisticLockingFailureException exception,
            HttpServletRequest request) {
        log.warn("Lock acquisition failed: {}", exception.getClass().getSimpleName());
        ResponseEntity<ErrorResponse> response = respond(ErrorCode.LOCK_TIMEOUT,
                "The account is busy; retry the request with the same Idempotency-Key",
                request);
        return ResponseEntity.status(response.getStatusCode())
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(response.getBody());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> handleIntegrityViolation(
            DataIntegrityViolationException exception,
            HttpServletRequest request) {
        log.warn("Data integrity violation", exception);
        return respond(ErrorCode.DATA_CONFLICT,
                "The request conflicts with the current state of the resource",
                request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(
            Exception exception, HttpServletRequest request) throws Exception {
        if (exception instanceof org.springframework.web.ErrorResponse frameworkError) {
            int status = frameworkError.getStatusCode().value();
            if (status < 500) {
                ErrorCode code = ErrorCode.forFrameworkStatus(status);
                return ResponseEntity.status(status).body(body(
                        code, status, safeFrameworkMessage(code), request, List.of()));
            }
        }
        if (isSecurityException(exception)) {
            // Let Spring Security's entry point and access-denied handler respond.
            throw exception;
        }
        log.error("Unhandled exception", exception);
        return respond(ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred", request);
    }

    private static boolean isSecurityException(Exception exception) {
        return exception.getClass().getName()
                .startsWith("org.springframework.security.");
    }

    private static String safeFrameworkMessage(ErrorCode code) {
        return switch (code) {
            case RESOURCE_NOT_FOUND -> "Resource not found";
            case METHOD_NOT_ALLOWED -> "HTTP method not supported for this resource";
            case NOT_ACCEPTABLE -> "Requested media type is not available";
            case UNSUPPORTED_MEDIA_TYPE -> "Content type is not supported";
            default -> "The request could not be processed";
        };
    }

    private static ResponseEntity<ErrorResponse> respond(
            ErrorCode code, String message, HttpServletRequest request) {
        return respond(code, message, request, List.of());
    }

    private static ResponseEntity<ErrorResponse> respond(
            ErrorCode code,
            String message,
            HttpServletRequest request,
            List<ErrorResponse.FieldError> fieldErrors) {
        return ResponseEntity.status(code.status()).body(body(
                code, code.status().value(), message, request, fieldErrors));
    }

    public static ErrorResponse body(
            ErrorCode code,
            int status,
            String message,
            HttpServletRequest request,
            List<ErrorResponse.FieldError> fieldErrors) {
        return new ErrorResponse(
                OffsetDateTime.now(),
                status,
                code.name(),
                message,
                request.getRequestURI(),
                MDC.get(CorrelationIdFilter.MDC_KEY),
                fieldErrors
        );
    }
}
