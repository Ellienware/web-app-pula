package com.momocircle.wallet;

import com.momocircle.controller.BusinessWalletController;
import com.momocircle.controller.WalletAdminController;
import com.momocircle.controller.WalletController;
import com.momocircle.ledger.LedgerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps ledger and wallet failures to HTTP responses for the wallet controllers only. The body never contains internal ids,
 * SQL, stack traces or other users' data; {@code code} is stable, {@code message} is safe to show. Everything else (401,
 * 429, validation, unexpected errors) still goes through the existing {@code GlobalExceptionHandler}.
 */
@RestControllerAdvice(assignableTypes = {WalletController.class, WalletAdminController.class, BusinessWalletController.class,
        com.momocircle.controller.SplitBillController.class,
        com.momocircle.controller.WalletCheckoutController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WalletExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(WalletExceptionAdvice.class);

    @ExceptionHandler(WalletException.class)
    public ResponseEntity<Map<String, Object>> wallet(WalletException ex) {
        return respond(ex.status(), ex.code(), ex.getMessage());
    }

    @ExceptionHandler(LedgerException.class)
    public ResponseEntity<Map<String, Object>> ledger(LedgerException ex) {
        return switch (ex.code()) {
            case INVALID_AMOUNT -> respond(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", ex.getMessage());
            case INVALID_CURRENCY -> respond(HttpStatus.BAD_REQUEST, "INVALID_CURRENCY", ex.getMessage());
            case CURRENCY_MISMATCH -> respond(HttpStatus.BAD_REQUEST, "CURRENCY_MISMATCH", ex.getMessage());
            case SELF_TRANSFER -> respond(HttpStatus.BAD_REQUEST, "SELF_TRANSFER", "You cannot send demo funds to yourself");
            case INSUFFICIENT_FUNDS -> respond(HttpStatus.CONFLICT, "INSUFFICIENT_FUNDS", "Insufficient simulated balance");
            case LIMIT_EXCEEDED -> respond(HttpStatus.UNPROCESSABLE_ENTITY, "LIMIT_EXCEEDED", ex.getMessage());
            case IDEMPOTENCY_CONFLICT -> respond(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", ex.getMessage());
            case ILLEGAL_STATE -> respond(HttpStatus.CONFLICT, "ILLEGAL_STATE", "This transaction cannot be changed in its current state");
            case NOT_OWNER -> respond(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have access to this wallet or transaction");
            case ACCOUNT_NOT_FOUND, NOT_FOUND -> respond(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found");
            case UNBALANCED -> {
                log.error("Ledger reported an unbalanced transaction: {}", ex.getMessage());
                yield respond(HttpStatus.INTERNAL_SERVER_ERROR, "LEDGER_ERROR", "Unexpected error");
            }
            case CONCURRENCY_FAILURE -> respond(HttpStatus.SERVICE_UNAVAILABLE, "TRY_AGAIN",
                    "The wallet is busy. Nothing was changed; please retry with the same request.");
        };
    }

    private static ResponseEntity<Map<String, Object>> respond(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("code", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
