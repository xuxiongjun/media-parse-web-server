package com.mediaparse.dto;

import java.util.ArrayList;
import java.util.List;

public class FetchImagesResponse {
    private String platform;
    private String title;
    private List<String> imageProxyUrls = new ArrayList<>();
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

    public List<String> getImageProxyUrls() {
        return imageProxyUrls;
    }

    public void setImageProxyUrls(List<String> imageProxyUrls) {
        this.imageProxyUrls = imageProxyUrls != null ? imageProxyUrls : new ArrayList<>();
    }

    public Long getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(Long expireAt) {
        this.expireAt = expireAt;
    }

    public static final class Builder {
        private final FetchImagesResponse target = new FetchImagesResponse();

        public Builder platform(String platform) {
            target.platform = platform;
            return this;
        }

        public Builder title(String title) {
            target.title = title;
            return this;
        }

        public Builder imageProxyUrls(List<String> imageProxyUrls) {
            target.setImageProxyUrls(imageProxyUrls);
            return this;
        }

        public Builder expireAt(Long expireAt) {
            target.expireAt = expireAt;
            return this;
        }

        public FetchImagesResponse build() {
            return target;
        }
    }
}
