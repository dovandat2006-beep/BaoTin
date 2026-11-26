package com.baotin.notification.api;

public class ApiFailure extends RuntimeException {
    private final int status;
    private final String code;

    public ApiFailure(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public ApiFailure(int status, String code) {
        this(status, code, code);
    }

    public int status() { return status; }
    public String code() { return code; }

    public static ApiFailure unauthorized() {
        return new ApiFailure(401, "UNAUTHORIZED", "Authentication required");
    }

    public static ApiFailure notFound() {
        return new ApiFailure(404, "NOT_FOUND", "Resource not found");
    }
}
