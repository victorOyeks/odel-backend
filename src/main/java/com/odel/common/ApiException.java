package com.odel.common;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** An error the screen can show: a stable code, a plain sentence, and optional per-field messages. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Map<String, String> fields;
    private final Map<String, Object> extra;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null, null);
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, String> fields, Map<String, Object> extra) {
        super(message);
        this.status = status;
        this.code = code;
        this.fields = fields;
        this.extra = extra;
    }

    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", "We could not find that.");
    }

    public static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Your role cannot do this.");
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException fields(HttpStatus status, String code, String message, Map<String, String> fields) {
        return new ApiException(status, code, message, fields, null);
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public Map<String, String> fields() { return fields; }
    public Map<String, Object> extra() { return extra; }
}
