package com.devprep.ai.api;

public class ApiException extends RuntimeException {
    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int getStatus() { return status; }
    public String getCode() { return code; }

    public static ApiException badRequest(String code, String message) { return new ApiException(400, code, message); }
    public static ApiException notFound(String code, String message) { return new ApiException(404, code, message); }
}
