package com.mediaparse.dto;

import jakarta.validation.constraints.NotBlank;

public class ParseRequest {
    @NotBlank(message = "url 不能为空")
    private String url;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
