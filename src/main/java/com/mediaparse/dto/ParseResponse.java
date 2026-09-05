package com.mediaparse.dto;

import java.util.ArrayList;
import java.util.List;

public class ParseResponse {
    private String platform;
    /** video | image */
    private String mediaType;
    private String title;
    private String author;
    private String coverProxyUrl;
    private String videoProxyUrl;
    private List<String> imageProxyUrls = new ArrayList<>();
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

    public String getMediaType() {
        return mediaType;
    }

    public void setMediaType(String mediaType) {
        this.mediaType = mediaType;
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

    public List<String> getImageProxyUrls() {
        return imageProxyUrls;
    }

    public void setImageProxyUrls(List<String> imageProxyUrls) {
        this.imageProxyUrls = imageProxyUrls != null ? imageProxyUrls : new ArrayList<>();
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

        public Builder mediaType(String mediaType) {
            target.mediaType = mediaType;
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

        public Builder imageProxyUrls(List<String> imageProxyUrls) {
            target.setImageProxyUrls(imageProxyUrls);
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
