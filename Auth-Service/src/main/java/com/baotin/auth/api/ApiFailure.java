package com.baotin.auth.api;

public class ApiFailure extends RuntimeException {
    private final int status;
    private final String code;
    public ApiFailure(int status,String code,String message) {super(message);this.status=status;this.code=code;}
    public int status(){return status;}
    public String code(){return code;}
    public static ApiFailure unauthorized(){return new ApiFailure(401,"INVALID_CREDENTIALS","Authentication failed");}
}
