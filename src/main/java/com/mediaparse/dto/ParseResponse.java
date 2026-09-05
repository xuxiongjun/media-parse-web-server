package com.mediaparse.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ParseResponse {
    private String platform;
    private String title;
    private String author;
    private String coverProxyUrl;
    private String videoProxyUrl;
    private Integer duration;
    private Long expireAt;
}
