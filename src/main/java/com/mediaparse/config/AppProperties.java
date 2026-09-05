package com.mediaparse.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private String corsOrigins = "*";
    private long mediaTokenTtlSeconds = 900;
    private int parseRateLimitPerMinute = 20;
    private Http http = new Http();
    private Cookies cookies = new Cookies();

    @Data
    public static class Http {
        private int connectTimeoutMs = 10000;
        private int readTimeoutMs = 20000;
        private String userAgent;
    }

    @Data
    public static class Cookies {
        private String douyin = "";
        private String xiaohongshu = "";
    }
}
