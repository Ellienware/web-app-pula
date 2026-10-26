package com.momocircle.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.converter.HttpMessageNotReadableException;
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus()).body(body(ex.getStatus(), ex.getMessage(), null, null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, fe ->
                        fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage(), (a, b) -> a));
        return ResponseEntity.badRequest().body(body(HttpStatus.BAD_REQUEST, "Validation failed", fieldErrors, null));
    }

    /**
     * Anything not already a handled {@link ApiException} or validation failure is, by
     * definition, unexpected — a real bug, not a normal client error. The client only ever sees
     * a generic message plus a requestId; the actual exception (message, stack trace, class) is
     * logged server-side against that same requestId, which is what makes a report like
     * "I got an error, requestId abc-123" actually traceable without exposing internals in the
     * response body (Rule: financial/system errors must be traceable without exposing secrets).
     */
@ExceptionHandler(HttpMessageNotReadableException.class)
public ResponseEntity<Map<String, Object>> handleUnreadableBody(
        HttpMessageNotReadableException ex) {
    // An unknown enum identifier (e.g. an unsupported businessType) gets a clear, field-level 400 that lists the allowed
    // values instead of the generic message. The submitted value is deliberately not echoed back.
    if (ex.getCause() instanceof com.fasterxml.jackson.databind.exc.InvalidFormatException ife
            && ife.getTargetType() != null && ife.getTargetType().isEnum()) {
        String field = ife.getPath().stream()
                .map(com.fasterxml.jackson.databind.JsonMappingException.Reference::getFieldName)
                .filter(java.util.Objects::nonNull)
                .reduce((first, second) -> second).orElse("value");
        String allowed = java.util.Arrays.stream(ife.getTargetType().getEnumConstants())
                .map(Object::toString).collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(body(HttpStatus.BAD_REQUEST, "Validation failed",
                Map.of(field, "Unsupported value. Allowed values: " + allowed), null));
    }
    return ResponseEntity.badRequest().body(
            body(HttpStatus.BAD_REQUEST, "Malformed request body", null, null)
    );
}
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleTooLarge(org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(body(HttpStatus.PAYLOAD_TOO_LARGE, "Image is too large (maximum 2 MB)", null, null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        String requestId = UUID.randomUUID().toString();
        log.error("Unhandled exception [requestId={}]", requestId, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", null, requestId));
    }

    private Map<String, Object> body(HttpStatus status, String message, Object details, String requestId) {
        Map<String, Object> map = new HashMap<>();
        map.put("timestamp", Instant.now().toString());
        map.put("status", status.value());
        map.put("error", status.getReasonPhrase());
        map.put("message", message);
        if (details != null) {
            map.put("details", details);
        }
        if (requestId != null) {
            map.put("requestId", requestId);
        }
        return map;
    }
}
