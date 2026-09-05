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
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
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

    private static final String PC_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

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
        String mobileUa = appProperties.getHttp().getUserAgent();
        if (mobileUa == null || mobileUa.isBlank()) {
            mobileUa = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1";
        }
        String configuredCookie = blankToEmpty(appProperties.getCookies().getDouyin());

        HttpFetcher.FetchResult expanded = httpFetcher.get(rawUrl, mobileUa, configuredCookie, Map.of(
                "Referer", "https://www.douyin.com/"
        ));
        String videoId = extractVideoId(expanded.finalUrl());
        if (videoId == null) {
            videoId = extractVideoId(expanded.body());
        }
        if (videoId == null) {
            throw new BusinessException("PARSE_FAILED", "未能从抖音链接中提取视频 ID");
        }

        String ttwid = obtainTtwid();
        String cookie = mergeCookies(configuredCookie, ttwid);

        MediaInfo fromDetail = tryWebAwemeDetail(videoId, cookie);
        if (isValid(fromDetail)) {
            return fromDetail;
        }

        // Fallback: HTML embedded JSON (older pages)
        String shareUrl = "https://www.iesdouyin.com/share/video/" + videoId + "/";
        HttpFetcher.FetchResult page = httpFetcher.get(shareUrl, mobileUa, cookie, Map.of(
                "Referer", "https://www.douyin.com/"
        ));
        MediaInfo fromPage = extractFromHtml(page.body(), videoId);
        if (isValid(fromPage)) {
            return fromPage;
        }

        MediaInfo fromFinal = extractFromHtml(expanded.body(), videoId);
        if (isValid(fromFinal)) {
            return fromFinal;
        }

        throw new BusinessException("PARSE_FAILED", "抖音解析失败，页面结构可能已变更或触发风控");
    }

    private String obtainTtwid() {
        try {
            String body = "{\"region\":\"cn\",\"aid\":1768,\"needFid\":false,\"service\":\"www.douyin.com\","
                    + "\"migrate_info\":{\"ticket\":\"\",\"source\":\"node\"},\"cbUrlProtocol\":\"https\",\"union\":true}";
            HttpFetcher.FetchResult result = httpFetcher.postJson(
                    "https://ttwid.bytedance.com/ttwid/union/register/",
                    body,
                    PC_UA,
                    null,
                    Map.of("Origin", "https://www.douyin.com", "Referer", "https://www.douyin.com/")
            );
            return result.firstCookiePair("ttwid");
        } catch (Exception ignored) {
            return null;
        }
    }

    private MediaInfo tryWebAwemeDetail(String videoId, String cookie) {
        try {
            String api = "https://www.douyin.com/aweme/v1/web/aweme/detail/"
                    + "?device_platform=webapp&aid=6383&channel=channel_pc_web"
                    + "&aweme_id=" + videoId
                    + "&pc_client_type=1&version_code=190500&version_name=19.5.0"
                    + "&cookie_enabled=true&screen_width=1920&screen_height=1080"
                    + "&browser_language=zh-CN&browser_platform=Win32&browser_name=Chrome"
                    + "&browser_version=122.0.0.0&browser_online=true&engine_name=Blink"
                    + "&engine_version=122.0.0.0&os_name=Windows&os_version=10"
                    + "&cpu_core_num=8&device_memory=8&platform=PC&downlink=10"
                    + "&effective_type=4g&round_trip_time=50";

            HttpFetcher.FetchResult result = httpFetcher.get(api, PC_UA, cookie, Map.of(
                    "Referer", "https://www.douyin.com/video/" + videoId,
                    "Accept", "application/json, text/plain, */*"
            ));
            if (result.code() >= 400 || result.body() == null || result.body().isBlank()) {
                return null;
            }
            JsonNode root = objectMapper.readTree(result.body());
            JsonNode detail = root.path("aweme_detail");
            if (detail.isMissingNode() || detail.isNull()) {
                return null;
            }
            return fromAwemeItem(detail);
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
            if (isValid(info)) {
                return info;
            }
        }

        Matcher renderMatcher = RENDER_DATA.matcher(html);
        if (renderMatcher.find()) {
            String decoded = URLDecoder.decode(renderMatcher.group(1).trim(), StandardCharsets.UTF_8);
            MediaInfo info = findAwemeDeep(objectMapper.readTree(decoded));
            if (isValid(info)) {
                return info;
            }
        }

        Matcher routerMatcher = ROUTER_DATA.matcher(html);
        if (routerMatcher.find()) {
            MediaInfo info = findAwemeDeep(objectMapper.readTree(routerMatcher.group(1)));
            if (isValid(info)) {
                return info;
            }
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
                if (isValid(info)) {
                    return info;
                }
            }
            if (node.has("aweme_detail") && node.get("aweme_detail").isObject()) {
                MediaInfo info = fromAwemeItem(node.get("aweme_detail"));
                if (isValid(info)) {
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
        JsonNode video = item.path("video");
        if (video.path("duration").canConvertToInt()) {
            int raw = video.path("duration").asInt();
            duration = raw > 1000 ? raw / 1000 : raw;
        } else if (item.path("duration").canConvertToInt()) {
            int raw = item.path("duration").asInt();
            duration = raw > 1000 ? raw / 1000 : raw;
        }

        String videoUrl = pickBestVideoUrl(video);
        String cover = firstUrl(video.path("origin_cover").path("url_list"));
        if (!hasText(cover)) {
            cover = firstUrl(video.path("cover").path("url_list"));
        }
        if (!hasText(cover)) {
            cover = firstUrl(video.path("dynamic_cover").path("url_list"));
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

    private String pickBestVideoUrl(JsonNode video) {
        Set<String> candidates = new LinkedHashSet<>();
        addUrls(candidates, video.path("play_addr").path("url_list"));
        addUrls(candidates, video.path("download_addr").path("url_list"));
        addUrls(candidates, video.path("play_addr_h264").path("url_list"));

        JsonNode bitRate = video.path("bit_rate");
        if (bitRate.isArray()) {
            for (JsonNode br : bitRate) {
                addUrls(candidates, br.path("play_addr").path("url_list"));
            }
        }

        String best = null;
        for (String url : candidates) {
            String normalized = normalizePlayUrl(url);
            if (!hasText(normalized)) {
                continue;
            }
            best = normalized;
            // Prefer non-watermarked streams
            if (!normalized.contains("playwm") && !normalized.contains("watermark=1")) {
                return normalized;
            }
        }
        return best;
    }

    private static void addUrls(Set<String> out, JsonNode list) {
        if (list != null && list.isArray()) {
            for (JsonNode n : list) {
                if (n != null && n.isTextual()) {
                    out.add(n.asText());
                }
            }
        }
    }

    private static String normalizePlayUrl(String url) {
        if (url == null) {
            return null;
        }
        String u = unescapeJsonUrl(url);
        if (u.contains("playwm")) {
            u = u.replace("playwm", "play");
        }
        return u;
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
            return unescapeJsonUrl(list.get(0).asText(null));
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

    private static String mergeCookies(String configured, String ttwidPair) {
        StringBuilder sb = new StringBuilder();
        if (hasText(configured)) {
            sb.append(configured.trim());
        }
        if (hasText(ttwidPair)) {
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ';') {
                sb.append("; ");
            }
            // avoid duplicate ttwid
            if (!sb.toString().contains("ttwid=")) {
                sb.append(ttwidPair);
            }
        }
        return sb.toString();
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean isValid(MediaInfo info) {
        return info != null && hasText(info.getVideoUrl());
    }
}
