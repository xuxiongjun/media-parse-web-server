package com.mediaparse.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediaparse.config.AppProperties;
import com.mediaparse.domain.MediaInfo;
import com.mediaparse.domain.Platform;
import com.mediaparse.exception.BusinessException;
import com.mediaparse.util.HttpFetcher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class XiaohongshuParser implements VideoParser {

    private static final Pattern NOTE_ID = Pattern.compile(
            "/(?:explore|discovery/item|item|note)/([a-zA-Z0-9]+)"
    );
    private static final Pattern VIDEO_URL = Pattern.compile(
            "https?://[^\"'\\s\\\\]+(?:sns-video|xhscdn)[^\"'\\s\\\\]+\\.(?:mp4|mov)[^\"'\\s\\\\]*",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern IMAGE_URL = Pattern.compile(
            "https?://[^\"'\\s\\\\]+(?:sns-webpic|xhscdn)[^\"'\\s\\\\]+\\.(?:jpg|jpeg|png|webp)[^\"'\\s\\\\]*",
            Pattern.CASE_INSENSITIVE
    );

    private final HttpFetcher httpFetcher;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public XiaohongshuParser(HttpFetcher httpFetcher, AppProperties appProperties, ObjectMapper objectMapper) {
        this.httpFetcher = httpFetcher;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public Platform platform() {
        return Platform.XIAOHONGSHU;
    }

    @Override
    public boolean supports(String url) {
        String lower = url.toLowerCase();
        return lower.contains("xiaohongshu.com")
                || lower.contains("xhslink.com")
                || lower.contains("xhslink.cn");
    }

    @Override
    public MediaInfo parse(String rawUrl) throws Exception {
        String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
        String cookie = appProperties.getCookies().getXiaohongshu();

        HttpFetcher.FetchResult expanded = httpFetcher.get(rawUrl, ua, cookie, Map.of(
                "Referer", "https://www.xiaohongshu.com/"
        ));

        // 短链落地页通常带 xsec_token，页面里已有完整笔记；勿改写成无签名的 explore URL
        MediaInfo fromExpanded = extractFromInitialState(expanded.body());
        if (usable(fromExpanded)) {
            return fromExpanded;
        }

        String finalUrl = expanded.finalUrl();
        String noteId = extractNoteId(finalUrl);
        if (noteId == null) {
            noteId = extractNoteId(expanded.body());
        }

        String pageUrl = preferSignedNoteUrl(finalUrl, noteId);
        HttpFetcher.FetchResult page = Objects.equals(pageUrl, finalUrl)
                ? expanded
                : httpFetcher.get(pageUrl, ua, cookie, Map.of(
                "Referer", "https://www.xiaohongshu.com/"
        ));

        MediaInfo fromState = extractFromInitialState(page.body());
        if (usable(fromState)) {
            return fromState;
        }

        MediaInfo fallback = extractByRegex(page.body(), noteId);
        if (!usable(fallback)) {
            fallback = extractByRegex(expanded.body(), noteId);
        }
        if (usable(fallback)) {
            return fallback;
        }

        throw new BusinessException("PARSE_FAILED",
                "小红书解析失败。链接可能已失效，或需要登录态 Cookie（配置 app.cookies.xiaohongshu）");
    }

    private static boolean usable(MediaInfo info) {
        return info != null && (info.hasVideo() || info.hasImages());
    }

    /** 保留短链跳转后的 xsec_token 等参数，避免被风控成「笔记暂时无法浏览」 */
    private String preferSignedNoteUrl(String finalUrl, String noteId) {
        if (hasText(finalUrl) && finalUrl.contains("xiaohongshu.com") && extractNoteId(finalUrl) != null) {
            return finalUrl;
        }
        if (!hasText(noteId)) {
            return finalUrl;
        }
        String query = extractSignedQuery(finalUrl);
        String base = "https://www.xiaohongshu.com/discovery/item/" + noteId;
        return hasText(query) ? base + "?" + query : base;
    }

    private static String extractSignedQuery(String url) {
        if (!hasText(url)) {
            return null;
        }
        int q = url.indexOf('?');
        if (q < 0 || q >= url.length() - 1) {
            return null;
        }
        return url.substring(q + 1);
    }

    private MediaInfo extractFromInitialState(String html) throws Exception {
        if (html == null || html.isBlank()) {
            return null;
        }
        int idx = html.indexOf("window.__INITIAL_STATE__");
        if (idx < 0) {
            return null;
        }
        int start = html.indexOf('{', idx);
        if (start < 0) {
            return null;
        }
        String json = sanitizeJson(cutJsonObject(html.substring(start)));
        try {
            JsonNode root = objectMapper.readTree(json);
            return findNoteVideo(root);
        } catch (Exception ignored) {
            return null;
        }
    }

    private MediaInfo findNoteVideo(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            // note detail shapes vary: note.noteDetailMap / noteData / video / imageList
            boolean looksLikeNote = node.has("title") || node.has("desc") || node.has("user")
                    || node.has("noteId") || node.has("id");
            if (looksLikeNote && (node.has("video") || node.has("imageList") || node.has("image_list"))) {
                MediaInfo info = fromNoteNode(node);
                if (info != null) {
                    return info;
                }
            }
            if (node.has("note") && node.get("note").isObject()) {
                MediaInfo info = fromNoteNode(node.get("note"));
                if (info != null) {
                    return info;
                }
            }
            if (node.has("noteDetailMap") && node.get("noteDetailMap").isObject()) {
                Iterator<JsonNode> values = node.get("noteDetailMap").elements();
                while (values.hasNext()) {
                    JsonNode entry = values.next();
                    MediaInfo info = findNoteVideo(entry.path("note"));
                    if (info == null) {
                        info = findNoteVideo(entry);
                    }
                    if (info != null) {
                        return info;
                    }
                }
            }
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                MediaInfo nested = findNoteVideo(fields.next().getValue());
                if (nested != null) {
                    return nested;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                MediaInfo nested = findNoteVideo(child);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private MediaInfo fromNoteNode(JsonNode note) {
        String title = firstNonBlank(
                text(note, "title"),
                text(note, "desc"),
                text(note, "displayTitle")
        );
        String author = firstNonBlank(
                text(note.path("user"), "nickname"),
                text(note.path("user"), "nickName"),
                text(note.path("author"), "nickname")
        );

        String videoUrl = null;
        JsonNode video = note.path("video");
        if (!video.isMissingNode()) {
            videoUrl = firstNonBlank(
                    text(video, "masterUrl"),
                    text(video.path("media"), "stream"),
                    firstUrl(video.path("media").path("stream").path("h264")),
                    firstUrl(video.path("media").path("stream").path("h265")),
                    text(video, "url")
            );
            // consumer.urlList style
            if (!hasText(videoUrl)) {
                videoUrl = firstUrl(video.path("consumer").path("originVideoKey"));
            }
            if (!hasText(videoUrl)) {
                JsonNode stream = video.path("media").path("stream");
                videoUrl = deepFirstMp4(stream);
            }
        }

        // imageList may contain live photo / video meta in some payloads
        if (!hasText(videoUrl)) {
            videoUrl = deepFirstMp4(note.path("video"));
        }

        List<String> imageUrls = extractImageUrls(note);

        String cover = firstNonBlank(
                text(note.path("imageList").path(0), "urlDefault"),
                text(note.path("imageList").path(0), "url"),
                text(note.path("image_list").path(0), "url"),
                text(video.path("image"), "firstFrameFileid"),
                imageUrls.isEmpty() ? null : imageUrls.get(0),
                deepFirstImage(note)
        );

        if (!hasText(videoUrl) && imageUrls.isEmpty()) {
            return null;
        }

        // 图文笔记：无视频或明确 normal 类型
        String type = firstNonBlank(text(note, "type"), text(note, "noteType"));
        boolean imagePost = !imageUrls.isEmpty()
                && (!hasText(videoUrl) || "normal".equalsIgnoreCase(type));

        if (imagePost) {
            return MediaInfo.builder()
                    .platform(Platform.XIAOHONGSHU)
                    .mediaType(MediaInfo.TYPE_IMAGE)
                    .title(hasText(title) ? title : "小红书图文")
                    .author(author == null ? "" : author)
                    .coverUrl(cover)
                    .imageUrls(imageUrls)
                    .build();
        }

        return MediaInfo.builder()
                .platform(Platform.XIAOHONGSHU)
                .mediaType(MediaInfo.TYPE_VIDEO)
                .title(hasText(title) ? title : "小红书笔记")
                .author(author == null ? "" : author)
                .coverUrl(cover)
                .videoUrl(normalizeUrl(videoUrl))
                .duration(null)
                .build();
    }

    private List<String> extractImageUrls(JsonNode note) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        collectImageList(urls, note.path("imageList"));
        collectImageList(urls, note.path("image_list"));
        return new ArrayList<>(urls);
    }

    private void collectImageList(LinkedHashSet<String> out, JsonNode list) {
        if (list == null || !list.isArray()) {
            return;
        }
        for (JsonNode img : list) {
            if (img == null || img.isNull()) {
                continue;
            }
            String url = firstNonBlank(
                    text(img, "urlDefault"),
                    text(img, "url"),
                    preferInfoListUrl(img.path("infoList")),
                    preferInfoListUrl(img.path("info_list")),
                    firstUrl(img.path("urlList")),
                    firstUrl(img.path("url_list")),
                    deepFirstImage(img)
            );
            if (hasText(url)) {
                out.add(normalizeUrl(url));
            }
        }
    }

    private MediaInfo extractByRegex(String html, String noteId) {
        if (html == null) {
            return null;
        }
        Matcher videoMatcher = VIDEO_URL.matcher(html);
        if (videoMatcher.find()) {
            String url = normalizeUrl(videoMatcher.group());
            return MediaInfo.builder()
                    .platform(Platform.XIAOHONGSHU)
                    .mediaType(MediaInfo.TYPE_VIDEO)
                    .title(noteId != null ? "小红书笔记 " + noteId : "小红书笔记")
                    .author("")
                    .videoUrl(url)
                    .build();
        }

        LinkedHashSet<String> images = new LinkedHashSet<>();
        Matcher imageMatcher = IMAGE_URL.matcher(html);
        while (imageMatcher.find()) {
            String url = normalizeUrl(imageMatcher.group());
            // 跳过明显缩略图场景
            if (url.contains("nd_prv") || url.contains("WB_PRV")) {
                continue;
            }
            images.add(url);
            if (images.size() >= 40) {
                break;
            }
        }
        if (images.isEmpty()) {
            return null;
        }
        List<String> list = new ArrayList<>(images);
        return MediaInfo.builder()
                .platform(Platform.XIAOHONGSHU)
                .mediaType(MediaInfo.TYPE_IMAGE)
                .title(noteId != null ? "小红书图文 " + noteId : "小红书图文")
                .author("")
                .coverUrl(list.get(0))
                .imageUrls(list)
                .build();
    }

    private static String preferInfoListUrl(JsonNode infoList) {
        if (infoList == null || !infoList.isArray()) {
            return null;
        }
        String fallback = null;
        for (JsonNode item : infoList) {
            String scene = text(item, "imageScene");
            String url = text(item, "url");
            if (!hasText(url)) {
                continue;
            }
            if ("WB_DFT".equalsIgnoreCase(scene) || "ND_DFT".equalsIgnoreCase(scene)) {
                return url;
            }
            if (fallback == null) {
                fallback = url;
            }
        }
        return fallback;
    }

    private String extractNoteId(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = NOTE_ID.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static String deepFirstMp4(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            String v = node.asText();
            if (v.startsWith("http") && (v.contains(".mp4") || v.contains("sns-video") || v.contains("xhscdn"))) {
                return v;
            }
            return null;
        }
        if (node.isObject()) {
            // prefer masterUrl / backupUrls
            if (node.has("masterUrl") && node.get("masterUrl").isTextual()) {
                return node.get("masterUrl").asText();
            }
            Iterator<JsonNode> elements = node.elements();
            while (elements.hasNext()) {
                String found = deepFirstMp4(elements.next());
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String found = deepFirstMp4(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String deepFirstImage(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            String v = node.asText();
            if (v.startsWith("http") && (v.contains("xhscdn") || v.contains(".jpg") || v.contains(".webp") || v.contains(".jpeg"))) {
                return v;
            }
            return null;
        }
        if (node.isObject() || node.isArray()) {
            for (JsonNode child : node) {
                String found = deepFirstImage(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String firstUrl(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual() && item.asText().startsWith("http")) {
                    return item.asText();
                }
                if (item.isObject()) {
                    String master = text(item, "masterUrl");
                    if (hasText(master)) {
                        return master;
                    }
                    String url = text(item, "url");
                    if (hasText(url)) {
                        return url;
                    }
                }
            }
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return null;
    }

    private static String sanitizeJson(String json) {
        return json.replaceAll("\\bundefined\\b", "null");
    }

    private static String cutJsonObject(String text) {
        int depth = 0;
        boolean inString = false;
        char prev = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && prev != '\\') {
                inString = !inString;
            }
            if (!inString) {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return text.substring(0, i + 1);
                    }
                }
            }
            prev = c;
        }
        return text;
    }

    private static String normalizeUrl(String url) {
        if (url == null) {
            return null;
        }
        String normalized = url.replace("\\u002F", "/")
                .replace("\\/", "/")
                .replace("&amp;", "&");
        if (normalized.startsWith("http://sns-") || normalized.startsWith("http://ci.xiaohongshu.com")) {
            normalized = "https://" + normalized.substring("http://".length());
        }
        return normalized;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText(null);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
