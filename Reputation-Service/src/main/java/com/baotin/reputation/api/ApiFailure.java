package com.baotin.reputation.api;

public class ApiFailure extends RuntimeException {
    private final int status;
    private final String code;

    public ApiFailure(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiFailure badRequest(String code, String message) {
        return new ApiFailure(400, code, message);
    }

    public static ApiFailure notFound() {
        return new ApiFailure(404, "REPUTATION_NOT_FOUND", "Target reputation does not exist");
    }

    public static ApiFailure conflict(String code, String message) {
        return new ApiFailure(409, code, message);
    }
}
