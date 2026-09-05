package com.mediaparse.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediaparse.config.AppProperties;
import com.mediaparse.domain.MediaInfo;
import com.mediaparse.domain.Platform;
import com.mediaparse.exception.BusinessException;
import com.mediaparse.util.HttpFetcher;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DouyinParser implements VideoParser {

    private static final Pattern ID_IN_PATH = Pattern.compile("/(?:video|share/video|note)/(\\d+)");
    private static final Pattern ID_IN_QUERY = Pattern.compile("[?&](?:modal_id|aweme_id|item_ids)=(\\d+)");
    private static final Pattern RENDER_DATA = Pattern.compile(
            "<script[^>]*id=\"RENDER_DATA\"[^>]*>(.*?)</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern ROUTER_DATA = Pattern.compile(
            "window\\._ROUTER_DATA\\s*=\\s*(\\{.*?\\})\\s*</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern ANY_PLAY_URL = Pattern.compile(
            "https?://[^\"'\\s\\\\]+(?:\\.mp4|video_id=|mime_type=video)[^\"'\\s\\\\]*",
            Pattern.CASE_INSENSITIVE
    );

    private final HttpFetcher httpFetcher;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public DouyinParser(HttpFetcher httpFetcher, AppProperties appProperties, ObjectMapper objectMapper) {
        this.httpFetcher = httpFetcher;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public Platform platform() {
        return Platform.DOUYIN;
    }

    @Override
    public boolean supports(String url) {
        String lower = url.toLowerCase();
        return lower.contains("douyin.com") || lower.contains("iesdouyin.com");
    }

    @Override
    public MediaInfo parse(String rawUrl) throws Exception {
        String ua = appProperties.getHttp().getUserAgent();
        String cookie = appProperties.getCookies().getDouyin();

        HttpFetcher.FetchResult expanded = httpFetcher.get(rawUrl, ua, cookie, Map.of(
                "Referer", "https://www.douyin.com/"
        ));
        String finalUrl = expanded.finalUrl();
        String videoId = extractVideoId(finalUrl);
        if (videoId == null) {
            videoId = extractVideoId(expanded.body());
        }
        if (videoId == null) {
            throw new BusinessException("PARSE_FAILED", "未能从抖音链接中提取视频 ID");
        }

        MediaInfo fromApi = tryItemInfoApi(videoId, ua, cookie);
        if (fromApi != null && hasText(fromApi.getVideoUrl())) {
            return fromApi;
        }

        String shareUrl = "https://www.iesdouyin.com/share/video/" + videoId;
        HttpFetcher.FetchResult page = httpFetcher.get(shareUrl, ua, cookie, Map.of(
                "Referer", "https://www.douyin.com/"
        ));
        MediaInfo fromPage = extractFromHtml(page.body(), videoId);
        if (fromPage != null && hasText(fromPage.getVideoUrl())) {
            return fromPage;
        }

        MediaInfo fromFinal = extractFromHtml(expanded.body(), videoId);
        if (fromFinal != null && hasText(fromFinal.getVideoUrl())) {
            return fromFinal;
        }

        throw new BusinessException("PARSE_FAILED", "抖音解析失败，页面结构可能已变更或触发风控");
    }

    private MediaInfo tryItemInfoApi(String videoId, String ua, String cookie) {
        try {
            String api = "https://www.iesdouyin.com/web/api/v2/aweme/iteminfo/?item_ids=" + videoId;
            HttpFetcher.FetchResult result = httpFetcher.get(api, ua, cookie, Map.of(
                    "Referer", "https://www.douyin.com/",
                    "Accept", "application/json, text/plain, */*"
            ));
            if (result.code() >= 400 || result.body() == null || result.body().isBlank()) {
                return null;
            }
            JsonNode root = objectMapper.readTree(result.body());
            JsonNode item = root.path("item_list").path(0);
            if (item.isMissingNode() || item.isNull()) {
                return null;
            }
            return fromAwemeItem(item);
        } catch (Exception ignored) {
            return null;
        }
    }

    private MediaInfo extractFromHtml(String html, String videoId) throws Exception {
        if (html == null || html.isBlank()) {
            return null;
        }

        Document doc = Jsoup.parse(html);
        Element renderEl = doc.getElementById("RENDER_DATA");
        if (renderEl != null && hasText(renderEl.data())) {
            String decoded = URLDecoder.decode(renderEl.data().trim(), StandardCharsets.UTF_8);
            MediaInfo info = findAwemeDeep(objectMapper.readTree(decoded));
            if (info != null && hasText(info.getVideoUrl())) {
                return info;
            }
        }

        Matcher renderMatcher = RENDER_DATA.matcher(html);
        if (renderMatcher.find()) {
            String encoded = renderMatcher.group(1).trim();
            String decoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
            JsonNode node = objectMapper.readTree(decoded);
            MediaInfo info = findAwemeDeep(node);
            if (info != null) {
                return info;
            }
        }

        Matcher routerMatcher = ROUTER_DATA.matcher(html);
        if (routerMatcher.find()) {
            JsonNode node = objectMapper.readTree(routerMatcher.group(1));
            MediaInfo info = findAwemeDeep(node);
            if (info != null) {
                return info;
            }
        }

        // Fallback: scan for mp4-like urls and drop watermark playwm marker
        Matcher urlMatcher = ANY_PLAY_URL.matcher(html);
        String best = null;
        while (urlMatcher.find()) {
            String candidate = unescapeJsonUrl(urlMatcher.group());
            if (candidate.contains("playwm")) {
                candidate = candidate.replace("playwm", "play");
            }
            if (candidate.contains("video") || candidate.endsWith(".mp4")) {
                best = candidate;
                if (!candidate.contains("playwm")) {
                    break;
                }
            }
        }
        if (best != null) {
            return MediaInfo.builder()
                    .platform(Platform.DOUYIN)
                    .title("抖音视频 " + videoId)
                    .author("")
                    .videoUrl(best)
                    .coverUrl(null)
                    .duration(null)
                    .build();
        }
        return null;
    }

    private MediaInfo findAwemeDeep(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            if (node.has("video") && (node.has("aweme_id") || node.has("desc") || node.has("author"))) {
                MediaInfo info = fromAwemeItem(node);
                if (info != null && hasText(info.getVideoUrl())) {
                    return info;
                }
            }
            if (node.has("aweme") && node.get("aweme").isObject()) {
                MediaInfo info = fromAwemeItem(node.get("aweme"));
                if (info != null && hasText(info.getVideoUrl())) {
                    return info;
                }
            }
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                MediaInfo nested = findAwemeDeep(fields.next().getValue());
                if (nested != null) {
                    return nested;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                MediaInfo nested = findAwemeDeep(child);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private MediaInfo fromAwemeItem(JsonNode item) {
        if (item == null || item.isMissingNode()) {
            return null;
        }
        String title = textOrEmpty(item, "desc");
        if (!hasText(title)) {
            title = textOrEmpty(item.path("share_info"), "share_title");
        }
        String author = textOrEmpty(item.path("author"), "nickname");
        Integer duration = null;
        if (item.path("video").path("duration").canConvertToInt()) {
            duration = item.path("video").path("duration").asInt() / 1000;
        }

        String videoUrl = firstUrl(item.path("video").path("play_addr").path("url_list"));
        if (!hasText(videoUrl)) {
            videoUrl = firstUrl(item.path("video").path("download_addr").path("url_list"));
        }
        if (!hasText(videoUrl)) {
            videoUrl = firstUrl(item.path("video").path("play_addr_h264").path("url_list"));
        }
        if (hasText(videoUrl) && videoUrl.contains("playwm")) {
            videoUrl = videoUrl.replace("playwm", "play");
        }

        String cover = firstUrl(item.path("video").path("origin_cover").path("url_list"));
        if (!hasText(cover)) {
            cover = firstUrl(item.path("video").path("cover").path("url_list"));
        }
        if (!hasText(cover)) {
            cover = firstUrl(item.path("video").path("dynamic_cover").path("url_list"));
        }

        if (!hasText(videoUrl)) {
            return null;
        }
        return MediaInfo.builder()
                .platform(Platform.DOUYIN)
                .title(hasText(title) ? title : "抖音视频")
                .author(author)
                .coverUrl(cover)
                .videoUrl(videoUrl)
                .duration(duration)
                .build();
    }

    private String extractVideoId(String text) {
        if (text == null) {
            return null;
        }
        Matcher m1 = ID_IN_PATH.matcher(text);
        if (m1.find()) {
            return m1.group(1);
        }
        Matcher m2 = ID_IN_QUERY.matcher(text);
        if (m2.find()) {
            return m2.group(1);
        }
        return null;
    }

    private static String firstUrl(JsonNode list) {
        if (list != null && list.isArray() && !list.isEmpty()) {
            String url = list.get(0).asText(null);
            return unescapeJsonUrl(url);
        }
        return null;
    }

    private static String textOrEmpty(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return "";
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static String unescapeJsonUrl(String url) {
        if (url == null) {
            return null;
        }
        return url.replace("\\u002F", "/")
                .replace("\\/", "/")
                .replace("&amp;", "&");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
