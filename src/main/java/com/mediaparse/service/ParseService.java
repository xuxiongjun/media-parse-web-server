package com.mediaparse.service;

import com.mediaparse.domain.MediaInfo;
import com.mediaparse.domain.Platform;
import com.mediaparse.domain.StoredMedia;
import com.mediaparse.dto.ParseResponse;
import com.mediaparse.exception.BusinessException;
import com.mediaparse.parser.VideoParser;
import com.mediaparse.util.LinkCleaner;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ParseService {

    private final List<VideoParser> parsers;
    private final MediaTokenService mediaTokenService;
    private final RateLimitService rateLimitService;

    public ParseService(List<VideoParser> parsers,
                        MediaTokenService mediaTokenService,
                        RateLimitService rateLimitService) {
        this.parsers = parsers;
        this.mediaTokenService = mediaTokenService;
        this.rateLimitService = rateLimitService;
    }

    public ParseResponse parse(String rawInput, String clientIp) {
        rateLimitService.checkParse(clientIp);

        String url = LinkCleaner.extractUrl(rawInput);
        Platform platform = LinkCleaner.detectPlatform(url);
        if (platform == Platform.UNKNOWN) {
            throw new BusinessException("UNSUPPORTED", "暂仅支持抖音、小红书分享链接");
        }

        VideoParser parser = parsers.stream()
                .filter(p -> p.platform() == platform)
                .findFirst()
                .orElseThrow(() -> new BusinessException("UNSUPPORTED", "暂不支持该平台"));

        MediaInfo info;
        try {
            info = parser.parse(url);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("PARSE_FAILED", "解析异常：" + safeMessage(ex));
        }

        if (info == null || info.getVideoUrl() == null || info.getVideoUrl().isBlank()) {
            throw new BusinessException("PARSE_FAILED", "未获取到可播放的视频地址");
        }

        String videoToken = mediaTokenService.store(StoredMedia.builder()
                .url(info.getVideoUrl())
                .contentTypeHint("video/mp4")
                .filename(buildFilename(info, "mp4"))
                .build());

        String coverProxy = null;
        if (info.getCoverUrl() != null && !info.getCoverUrl().isBlank()) {
            String coverToken = mediaTokenService.store(StoredMedia.builder()
                    .url(info.getCoverUrl())
                    .contentTypeHint("image/jpeg")
                    .filename(buildFilename(info, "jpg"))
                    .build());
            coverProxy = "/api/media/" + coverToken;
        }

        return ParseResponse.builder()
                .platform(platform.name().toLowerCase())
                .title(info.getTitle())
                .author(info.getAuthor())
                .coverProxyUrl(coverProxy)
                .videoProxyUrl("/api/media/" + videoToken)
                .duration(info.getDuration())
                .expireAt(mediaTokenService.expireAtMillis())
                .build();
    }

    private static String buildFilename(MediaInfo info, String ext) {
        String base = info.getTitle() == null ? "video" : info.getTitle();
        base = base.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (base.length() > 40) {
            base = base.substring(0, 40);
        }
        return base + "." + ext;
    }

    private static String safeMessage(Exception ex) {
        String msg = ex.getMessage();
        if (msg == null || msg.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return msg.length() > 120 ? msg.substring(0, 120) : msg;
    }
}
