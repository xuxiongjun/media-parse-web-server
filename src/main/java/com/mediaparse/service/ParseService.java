package com.mediaparse.service;

import com.mediaparse.domain.MediaInfo;
import com.mediaparse.domain.Platform;
import com.mediaparse.domain.StoredMedia;
import com.mediaparse.dto.ParseResponse;
import com.mediaparse.exception.BusinessException;
import com.mediaparse.parser.VideoParser;
import com.mediaparse.util.LinkCleaner;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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

        if (info == null || (!info.hasVideo() && !info.hasImages())) {
            throw new BusinessException("PARSE_FAILED", "未获取到可预览的媒体资源");
        }

        String mediaType = info.hasImages() && !info.hasVideo()
                ? MediaInfo.TYPE_IMAGE
                : (info.getMediaType() != null ? info.getMediaType() : MediaInfo.TYPE_VIDEO);
        if (info.hasImages() && MediaInfo.TYPE_IMAGE.equals(info.getMediaType())) {
            mediaType = MediaInfo.TYPE_IMAGE;
        }

        String videoProxy = null;
        if (info.hasVideo()) {
            String videoToken = mediaTokenService.store(StoredMedia.builder()
                    .url(info.getVideoUrl())
                    .contentTypeHint("video/mp4")
                    .filename(buildFilename(info, "mp4", null))
                    .build());
            videoProxy = "/api/media/" + videoToken;
        }

        List<String> imageProxies = new ArrayList<>();
        if (info.hasImages()) {
            int idx = 1;
            for (String imageUrl : info.getImageUrls()) {
                if (imageUrl == null || imageUrl.isBlank()) {
                    continue;
                }
                String token = mediaTokenService.store(StoredMedia.builder()
                        .url(imageUrl)
                        .contentTypeHint("image/jpeg")
                        .filename(buildFilename(info, "jpg", idx))
                        .build());
                imageProxies.add("/api/media/" + token);
                idx++;
            }
        }

        String coverProxy = null;
        if (info.getCoverUrl() != null && !info.getCoverUrl().isBlank()) {
            String coverToken = mediaTokenService.store(StoredMedia.builder()
                    .url(info.getCoverUrl())
                    .contentTypeHint("image/jpeg")
                    .filename(buildFilename(info, "jpg", null))
                    .build());
            coverProxy = "/api/media/" + coverToken;
        } else if (!imageProxies.isEmpty()) {
            coverProxy = imageProxies.get(0);
        }

        if (MediaInfo.TYPE_IMAGE.equals(mediaType) && imageProxies.isEmpty()) {
            throw new BusinessException("PARSE_FAILED", "图文作品未解析到图片");
        }

        return ParseResponse.builder()
                .platform(platform.name().toLowerCase())
                .mediaType(mediaType)
                .title(info.getTitle())
                .author(info.getAuthor())
                .coverProxyUrl(coverProxy)
                .videoProxyUrl(videoProxy)
                .imageProxyUrls(imageProxies)
                .duration(info.getDuration())
                .expireAt(mediaTokenService.expireAtMillis())
                .build();
    }

    private static String buildFilename(MediaInfo info, String ext, Integer index) {
        String base = info.getTitle() == null ? "media" : info.getTitle();
        // 去掉控制字符与 Windows/浏览器非法文件名字符，避免 File System Access 报 Name is not allowed
        base = base.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F\\x7F]", "_")
                .replaceAll("[\\u200B-\\u200F\\u2028\\u2029\\uFEFF]", "")
                .replaceAll("[.\\s]+$", "")
                .trim();
        if (base.isBlank() || ".".equals(base) || "..".equals(base)) {
            base = "media";
        }
        if (base.length() > 40) {
            base = base.substring(0, 40).replaceAll("[.\\s]+$", "");
            if (base.isBlank()) {
                base = "media";
            }
        }
        if (index != null) {
            base = base + "_" + index;
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
