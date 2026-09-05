package com.mediaparse.domain;

public class StoredMedia {
    private String url;
    private String contentTypeHint;
    private String filename;

    public static Builder builder() {
        return new Builder();
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getContentTypeHint() {
        return contentTypeHint;
    }

    public void setContentTypeHint(String contentTypeHint) {
        this.contentTypeHint = contentTypeHint;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public static final class Builder {
        private final StoredMedia target = new StoredMedia();

        public Builder url(String url) {
            target.url = url;
            return this;
        }

        public Builder contentTypeHint(String contentTypeHint) {
            target.contentTypeHint = contentTypeHint;
            return this;
        }

        public Builder filename(String filename) {
            target.filename = filename;
            return this;
        }

        public StoredMedia build() {
            return target;
        }
    }
}
