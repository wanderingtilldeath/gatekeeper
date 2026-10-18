package com.gatekeeper.auth.web;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final HttpStatus status;

    private ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() { return status; }

    public static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, message); }

    public static ApiException unauthorized(String message) { return new ApiException(HttpStatus.UNAUTHORIZED, message); }
}
