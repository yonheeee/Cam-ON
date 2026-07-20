package com.plaiground.global.apiresponse;

public record ApiResponse<T>(T data) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(data);
    }
}
