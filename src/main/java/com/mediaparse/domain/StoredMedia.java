package com.mediaparse.domain;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StoredMedia {
    private String url;
    private String contentTypeHint;
    private String filename;
}
