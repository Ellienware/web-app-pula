package com.momocircle.wallet;

import org.springframework.http.HttpStatus;

/** A wallet-API failure with a stable machine-readable {@code code} the web/mobile clients can branch on. Messages are user-safe. */
public class WalletException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String code;

    public WalletException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    public static WalletException notFound(String code, String message) { return new WalletException(HttpStatus.NOT_FOUND, code, message); }
    public static WalletException badRequest(String code, String message) { return new WalletException(HttpStatus.BAD_REQUEST, code, message); }
    public static WalletException conflict(String code, String message) { return new WalletException(HttpStatus.CONFLICT, code, message); }
    public static WalletException gone(String code, String message) { return new WalletException(HttpStatus.GONE, code, message); }
}
