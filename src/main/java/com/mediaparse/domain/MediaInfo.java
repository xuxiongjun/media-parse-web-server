package com.mediaparse.domain;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MediaInfo {
    private Platform platform;
    private String title;
    private String author;
    private String coverUrl;
    private String videoUrl;
    private Integer duration;
}
