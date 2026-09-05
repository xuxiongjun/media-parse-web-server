package com.mediaparse.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ParseRequest {
    @NotBlank(message = "url 不能为空")
    private String url;
}
