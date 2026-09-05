package com.mediaparse.domain;

public class MediaInfo {
    private Platform platform;
    private String title;
    private String author;
    private String coverUrl;
    private String videoUrl;
    private Integer duration;

    public static Builder builder() {
        return new Builder();
    }

    public Platform getPlatform() {
        return platform;
    }

    public void setPlatform(Platform platform) {
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

    public String getCoverUrl() {
        return coverUrl;
    }

    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl;
    }

    public String getVideoUrl() {
        return videoUrl;
    }

    public void setVideoUrl(String videoUrl) {
        this.videoUrl = videoUrl;
    }

    public Integer getDuration() {
        return duration;
    }

    public void setDuration(Integer duration) {
        this.duration = duration;
    }

    public static final class Builder {
        private final MediaInfo target = new MediaInfo();

        public Builder platform(Platform platform) {
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

        public Builder coverUrl(String coverUrl) {
            target.coverUrl = coverUrl;
            return this;
        }

        public Builder videoUrl(String videoUrl) {
            target.videoUrl = videoUrl;
            return this;
        }

        public Builder duration(Integer duration) {
            target.duration = duration;
            return this;
        }

        public MediaInfo build() {
            return target;
        }
    }
}
