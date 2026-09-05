package com.mediaparse.dto;

public class ParseResponse {
    private String platform;
    private String title;
    private String author;
    private String coverProxyUrl;
    private String videoProxyUrl;
    private Integer duration;
    private Long expireAt;

    public static Builder builder() {
        return new Builder();
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getCoverProxyUrl() {
        return coverProxyUrl;
    }

    public void setCoverProxyUrl(String coverProxyUrl) {
        this.coverProxyUrl = coverProxyUrl;
    }

    public String getVideoProxyUrl() {
        return videoProxyUrl;
    }

    public void setVideoProxyUrl(String videoProxyUrl) {
        this.videoProxyUrl = videoProxyUrl;
    }

    public Integer getDuration() {
        return duration;
    }

    public void setDuration(Integer duration) {
        this.duration = duration;
    }

    public Long getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(Long expireAt) {
        this.expireAt = expireAt;
    }

    public static final class Builder {
        private final ParseResponse target = new ParseResponse();

        public Builder platform(String platform) {
            target.platform = platform;
            return this;
        }

        public Builder title(String title) {
            target.title = title;
            return this;
        }

        public Builder author(String author) {
            target.author = author;
            return this;
        }

        public Builder coverProxyUrl(String coverProxyUrl) {
            target.coverProxyUrl = coverProxyUrl;
            return this;
        }

        public Builder videoProxyUrl(String videoProxyUrl) {
            target.videoProxyUrl = videoProxyUrl;
            return this;
        }

        public Builder duration(Integer duration) {
            target.duration = duration;
            return this;
        }

        public Builder expireAt(Long expireAt) {
            target.expireAt = expireAt;
            return this;
        }

        public ParseResponse build() {
            return target;
        }
    }
}
