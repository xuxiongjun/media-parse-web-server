package com.mediaparse.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediaparse.config.AppProperties;
import com.mediaparse.domain.MediaInfo;
import com.mediaparse.domain.Platform;
import com.mediaparse.exception.BusinessException;
import com.mediaparse.util.HttpFetcher;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class XiaohongshuParser implements VideoParser {

    private static final Pattern NOTE_ID = Pattern.compile(
            "/(?:explore|discovery/item|item)/([a-zA-Z0-9]+)"
    );
    private static final Pattern INITIAL_STATE = Pattern.compile(
            "window\\.__INITIAL_STATE__\\s*=\\s*(\\{.+?\\})\\s*(?:</script>|;\\s*</script>)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern VIDEO_URL = Pattern.compile(
            "https?://[^\"'\\s\\\\]+(?:sns-video|xhscdn)[^\"'\\s\\\\]+\\.(?:mp4|mov)[^\"'\\s\\\\]*",
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
        return lower.contains("xiaohongshu.com") || lower.contains("xhslink.com");
    }

    @Override
    public MediaInfo parse(String rawUrl) throws Exception {
        String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
        String cookie = appProperties.getCookies().getXiaohongshu();

        HttpFetcher.FetchResult expanded = httpFetcher.get(rawUrl, ua, cookie, Map.of(
                "Referer", "https://www.xiaohongshu.com/"
        ));
        String finalUrl = expanded.finalUrl();
        String noteId = extractNoteId(finalUrl);
        if (noteId == null) {
            noteId = extractNoteId(expanded.body());
        }

        String pageUrl = noteId != null
                ? "https://www.xiaohongshu.com/explore/" + noteId
                : finalUrl;

        HttpFetcher.FetchResult page = pageUrl.equals(finalUrl)
                ? expanded
                : httpFetcher.get(pageUrl, ua, cookie, Map.of(
                "Referer", "https://www.xiaohongshu.com/"
        ));

        MediaInfo fromState = extractFromInitialState(page.body());
        if (fromState != null && hasText(fromState.getVideoUrl())) {
            return fromState;
        }

        MediaInfo fallback = extractByRegex(page.body(), noteId);
        if (fallback != null && hasText(fallback.getVideoUrl())) {
            return fallback;
        }

        throw new BusinessException("PARSE_FAILED",
                "小红书解析失败。可能需要登录态 Cookie（配置 app.cookies.xiaohongshu）或页面结构已变更");
    }

    private MediaInfo extractFromInitialState(String html) throws Exception {
        if (html == null || html.isBlank()) {
            return null;
        }
        Matcher matcher = INITIAL_STATE.matcher(html);
        if (!matcher.find()) {
            // Some pages embed JSON with undefined literals
            int idx = html.indexOf("window.__INITIAL_STATE__");
            if (idx < 0) {
                return null;
            }
            int start = html.indexOf('{', idx);
            if (start < 0) {
                return null;
            }
            String jsonish = sanitizeJson(html.substring(start));
            JsonNode root = objectMapper.readTree(cutJsonObject(jsonish));
            return findNoteVideo(root);
        }
        String json = sanitizeJson(matcher.group(1));
        JsonNode root = objectMapper.readTree(json);
        return findNoteVideo(root);
    }

    private MediaInfo findNoteVideo(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            // note detail shapes vary: note.noteDetailMap / noteData / video
            if (node.has("video") && (node.has("title") || node.has("desc") || node.has("user"))) {
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
            videoUrl = deepFirstMp4(note);
        }

        String cover = firstNonBlank(
                text(note.path("imageList").path(0), "urlDefault"),
                text(note.path("imageList").path(0), "url"),
                text(video.path("image"), "firstFrameFileid"),
                deepFirstImage(note)
        );

        if (!hasText(videoUrl)) {
            return null;
        }
        return MediaInfo.builder()
                .platform(Platform.XIAOHONGSHU)
                .title(hasText(title) ? title : "小红书笔记")
                .author(author == null ? "" : author)
                .coverUrl(cover)
                .videoUrl(normalizeUrl(videoUrl))
                .duration(null)
                .build();
    }

    private MediaInfo extractByRegex(String html, String noteId) {
        if (html == null) {
            return null;
        }
        Matcher matcher = VIDEO_URL.matcher(html);
        if (matcher.find()) {
            String url = normalizeUrl(matcher.group());
            return MediaInfo.builder()
                    .platform(Platform.XIAOHONGSHU)
                    .title(noteId != null ? "小红书笔记 " + noteId : "小红书笔记")
                    .author("")
                    .videoUrl(url)
                    .build();
        }
        return null;
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
        return json.replace(":undefined", ":null")
                .replace(": undefined", ": null");
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
        return url.replace("\\u002F", "/")
                .replace("\\/", "/")
                .replace("&amp;", "&");
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
