package com.mediaparse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private String corsOrigins = "*";
    private long mediaTokenTtlSeconds = 3600;
    private int parseRateLimitPerMinute = 120;
    private Http http = new Http();
    private Cookies cookies = new Cookies();

    public String getCorsOrigins() {
        return corsOrigins;
    }

    public void setCorsOrigins(String corsOrigins) {
        this.corsOrigins = corsOrigins;
    }

    public long getMediaTokenTtlSeconds() {
        return mediaTokenTtlSeconds;
    }

    public void setMediaTokenTtlSeconds(long mediaTokenTtlSeconds) {
        this.mediaTokenTtlSeconds = mediaTokenTtlSeconds;
    }

    public int getParseRateLimitPerMinute() {
        return parseRateLimitPerMinute;
    }

    public void setParseRateLimitPerMinute(int parseRateLimitPerMinute) {
        this.parseRateLimitPerMinute = parseRateLimitPerMinute;
    }

    public Http getHttp() {
        return http;
    }

    public void setHttp(Http http) {
        this.http = http;
    }

    public Cookies getCookies() {
        return cookies;
    }

    public void setCookies(Cookies cookies) {
        this.cookies = cookies;
    }

    public static class Http {
        private int connectTimeoutMs = 10000;
        private int readTimeoutMs = 20000;
        private String userAgent;

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }

        public String getUserAgent() {
            return userAgent;
        }

        public void setUserAgent(String userAgent) {
            this.userAgent = userAgent;
        }
    }

    public static class Cookies {
        private String douyin = "";
        private String xiaohongshu = "";

        public String getDouyin() {
            return douyin;
        }

        public void setDouyin(String douyin) {
            this.douyin = douyin;
        }

        public String getXiaohongshu() {
            return xiaohongshu;
        }

        public void setXiaohongshu(String xiaohongshu) {
            this.xiaohongshu = xiaohongshu;
        }
    }
}
