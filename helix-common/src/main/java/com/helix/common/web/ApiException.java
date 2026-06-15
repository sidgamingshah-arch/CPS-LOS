package com.helix.common.web;

import org.springframework.http.HttpStatus;

/** Carries an HTTP status with a business error so handlers can map cleanly. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    /** Used when an AI capability or autonomy boundary is violated (PRD §11). */
    public static ApiException forbiddenAutonomy(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message);
    }

    /** Authentication failure — bad credentials, missing / invalid / expired token. */
    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, message);
    }

    public HttpStatus getStatus() {
        return status;
    }
}
