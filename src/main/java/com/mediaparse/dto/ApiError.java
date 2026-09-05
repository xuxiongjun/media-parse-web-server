package com.mediaparse.dto;

public class ApiError {
    private String code;
    private String message;

    public static Builder builder() {
        return new Builder();
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public static final class Builder {
        private final ApiError target = new ApiError();

        public Builder code(String code) {
            target.code = code;
            return this;
        }

        public Builder message(String message) {
            target.message = message;
            return this;
        }

        public ApiError build() {
            return target;
        }
    }
}
