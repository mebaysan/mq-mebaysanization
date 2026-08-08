package com.baysansoft.mqmanager.web;

import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders every failure as {@link ApiError}, so the client speaks exactly one error vocabulary.
 *
 * <p>Extending {@code ResponseEntityExceptionHandler} backs off Boot's own ProblemDetail advice (it is
 * {@code @ConditionalOnMissingBean} on this type), and overriding the single {@code createResponseEntity}
 * funnel converts all ~20 built-in Spring MVC exception handlers at once instead of writing 20 handlers.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Converts every built-in Spring MVC error (including NoResourceFoundException) to our shape. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body,
                                                          HttpHeaders headers,
                                                          HttpStatusCode statusCode,
                                                          WebRequest request) {
        String message = null;
        if (body instanceof ProblemDetail problem) {
            message = problem.getDetail() != null ? problem.getDetail() : problem.getTitle();
        }
        HttpStatus status = HttpStatus.valueOf(statusCode.value());
        return new ResponseEntity<>(
                ApiError.of(status.value(), status.getReasonPhrase(), message, null, path(request)),
                headers,
                statusCode);
    }

    /** Bean-validation failures on a request body, reported with the offending field names. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        String detail = ex.getBindingResult().getAllErrors().stream()
                .map(error -> error instanceof FieldError fieldError
                        ? fieldError.getField() + ": " + fieldError.getDefaultMessage()
                        : error.getDefaultMessage())
                .distinct()
                .collect(Collectors.joining("; "));

        return new ResponseEntity<>(
                ApiError.of(HttpStatus.BAD_REQUEST.value(), "Bad Request", detail, "VALIDATION_FAILED",
                        path(request)),
                headers,
                HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MqOperationException.class)
    ResponseEntity<ApiError> handleMqOperation(MqOperationException ex, WebRequest request) {
        // The message is already human-readable and carries no credentials; the cause is logged at
        // DEBUG so a stack trace never reaches the client but is still available when investigating.
        log.warn("Broker operation failed [{}]: {}", ex.getCode(), ex.getMessage());
        log.debug("Broker operation failure detail", ex);
        return ResponseEntity.status(ex.getStatus())
                .body(ApiError.of(ex.getStatus().value(), ex.getStatus().getReasonPhrase(),
                        ex.getMessage(), ex.getCode(), path(request)));
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiError> handleNotFound(NotFoundException ex, WebRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(HttpStatus.NOT_FOUND.value(), "Not Found", ex.getMessage(),
                        ex.getCode(), path(request)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex, WebRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(HttpStatus.BAD_REQUEST.value(), "Bad Request", ex.getMessage(),
                        "INVALID_REQUEST", path(request)));
    }

    /** A duplicate profile name is a conflict, not a server error. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex, WebRequest request) {
        log.debug("Data integrity violation", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(HttpStatus.CONFLICT.value(), "Conflict",
                        "That change conflicts with an existing record. Connection names must be unique.",
                        "DUPLICATE_NAME", path(request)));
    }

    /** Last resort. The stack trace is logged, never serialized. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal Server Error",
                        "Something went wrong. Check the application log for details.",
                        "INTERNAL_ERROR", path(request)));
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest servletRequest
                ? servletRequest.getRequest().getRequestURI()
                : null;
    }
}
