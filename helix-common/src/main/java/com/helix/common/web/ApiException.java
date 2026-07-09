package com.helix.common.web;

import org.springframework.http.HttpStatus;

/** Carries an HTTP status with a business error so handlers can map cleanly. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private boolean postureDeny;
    private String postureActor;

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

    /**
     * Denied because the directory is unavailable and the governance posture is FAIL-CLOSED
     * (G7). Carries the actor so the exception handler can audit the posture deny once the
     * caller's transaction has rolled back (SQLite single-writer — no inline audit here).
     */
    public static ApiException forbiddenPosture(String actor) {
        ApiException e = new ApiException(HttpStatus.FORBIDDEN,
                "Actor '" + actor + "' cannot be authorised — the ACTOR_ROLE directory is unavailable "
                + "and the governance posture is FAIL-CLOSED; the request is denied");
        e.postureDeny = true;
        e.postureActor = actor;
        return e;
    }

    public boolean isPostureDeny() {
        return postureDeny;
    }

    public String getPostureActor() {
        return postureActor;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
