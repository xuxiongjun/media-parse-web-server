package com.mediaparse.parser;

import com.mediaparse.domain.MediaInfo;
import com.mediaparse.domain.Platform;

public interface VideoParser {
    Platform platform();

    boolean supports(String url);

    MediaInfo parse(String rawUrl) throws Exception;
}
