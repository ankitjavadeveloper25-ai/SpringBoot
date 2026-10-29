package com.winterarc.server.exception;

public class RazorpayApiException extends RuntimeException {

    private final int statusCode;
    private final String errorCode;

    public RazorpayApiException(String message) {
        super(message);
        this.statusCode = 500;
        this.errorCode = "GATEWAY_ERROR";
    }

    public RazorpayApiException(int statusCode, String errorCode, String message) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    public RazorpayApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 500;
        this.errorCode = "GATEWAY_ERROR";
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
