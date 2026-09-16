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
    /** 从 webpic 鉴权链截取资源 path（可含 notes_pre_post/spectrum 前缀） */
    private static final Pattern WEB_PIC_TOKEN = Pattern.compile(
            "https?://(?:sns-webpic[^/\"'\\s]+)/\\d+/[0-9a-f]+/([^!\"'\\s]+)!",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TOKEN_1040 = Pattern.compile(
            "((?:notes_pre_post|notes_uhdr|spectrum)/)?(1040g[a-zA-Z0-9]+)",
            Pattern.CASE_INSENSITIVE
    );
    /** 现代小红书 CDN 常无 .mp4 后缀，只匹配 sns-video 域 */
    private static final Pattern VIDEO_URL = Pattern.compile(
            "https?://(?:[^/\"'\\s\\\\]+\\.)?sns-video[^/\"'\\s\\\\]*/[^\"'\\s\\\\]+",
            Pattern.CASE_INSENSITIVE
    );
    /** 图片 CDN 常无 .jpg 后缀（如 !nd_dft_wlteh_jpg_3），排除静态资源域 */
    private static final Pattern IMAGE_URL = Pattern.compile(
            "https?://(?:sns-webpic[^/\"'\\s\\\\]*|ci\\.xiaohongshu\\.com)[^\"'\\s\\\\]+",
            Pattern.CASE_INSENSITIVE
    );

    private static final String MOBILE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
    private static final String PC_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/122.0.0.0 Safari/537.36";

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
        String cookie = blankToEmpty(appProperties.getCookies().getXiaohongshu());
        String configuredUa = appProperties.getHttp().getUserAgent();
        List<String> userAgents = new ArrayList<>();
        if (hasText(configuredUa)) {
            userAgents.add(configuredUa);
        }
        userAgents.add(MOBILE_UA);
        userAgents.add(PC_UA);

        MediaInfo best = null;
        for (String ua : userAgents) {
            MediaInfo info = parseWithUa(rawUrl, ua, cookie);
            if (usable(info)) {
                return info;
            }
            if (info != null) {
                best = info;
            }
        }

        if (usable(best)) {
            return best;
        }

        throw new BusinessException("PARSE_FAILED",
                "小红书解析失败。链接可能已失效，或需要登录态 Cookie（配置 app.cookies.xiaohongshu）");
    }

    private MediaInfo parseWithUa(String rawUrl, String ua, String cookie) throws Exception {
        HttpFetcher.SessionClient session = httpFetcher.openSession();
        Map<String, String> headers = Map.of("Referer", "https://www.xiaohongshu.com/");

        HttpFetcher.FetchResult expanded = session.get(rawUrl, ua, cookie, headers);
        MediaInfo fromExpanded = extractFromInitialState(expanded.body());
        if (usable(fromExpanded)) {
            return fromExpanded;
        }

        String finalUrl = expanded.finalUrl();
        // 仅从落地 URL 取笔记 ID；首页 HTML 里有大量推荐笔记 ID，不能当成本链目标
        String noteId = extractNoteId(finalUrl);
        if (noteId == null && isNoteLandingUrl(finalUrl)) {
            noteId = extractNoteId(expanded.body());
        }

        for (String pageUrl : candidateNoteUrls(finalUrl, noteId, expanded.body())) {
            HttpFetcher.FetchResult page = Objects.equals(pageUrl, finalUrl)
                    ? expanded
                    : session.get(pageUrl, ua, cookie, headers);

            MediaInfo fromState = extractFromInitialState(page.body());
            if (usable(fromState)) {
                return fromState;
            }

            MediaInfo fallback = extractByRegex(page.body(), noteId);
            if (usable(fallback)) {
                return fallback;
            }
        }

        MediaInfo regexExpanded = extractByRegex(expanded.body(), noteId);
        if (usable(regexExpanded)) {
            return regexExpanded;
        }
        return fromExpanded;
    }

    private static boolean isNoteLandingUrl(String url) {
        if (!hasText(url)) {
            return false;
        }
        return extractNoteIdStatic(url) != null;
    }

    private static String extractNoteIdStatic(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = NOTE_ID.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private List<String> candidateNoteUrls(String finalUrl, String noteId, String html) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (hasText(finalUrl) && finalUrl.contains("xiaohongshu.com") && extractNoteId(finalUrl) != null) {
            urls.add(finalUrl);
        }

        // 只有已从落地 URL 确认 noteId 时，才用页面里的 xsec_token 组装详情地址
        if (!hasText(noteId)) {
            return new ArrayList<>(urls);
        }

        String query = firstNonBlank(extractSignedQuery(finalUrl), extractSignedQueryFromHtml(html));
        if (hasText(query)) {
            urls.add("https://www.xiaohongshu.com/explore/" + noteId + "?" + query);
            urls.add("https://www.xiaohongshu.com/discovery/item/" + noteId + "?" + query);
        }
        urls.add("https://www.xiaohongshu.com/explore/" + noteId);
        urls.add("https://www.xiaohongshu.com/discovery/item/" + noteId);
        return new ArrayList<>(urls);
    }

    private static String extractSignedQuery(String url) {
        if (!hasText(url)) {
            return null;
        }
        int q = url.indexOf('?');
        if (q < 0 || q >= url.length() - 1) {
            return null;
        }
        String query = url.substring(q + 1);
        return query.toLowerCase().contains("xsec_token") ? query : null;
    }

    private static String extractSignedQueryFromHtml(String html) {
        if (!hasText(html)) {
            return null;
        }
        Matcher matcher = Pattern.compile(
                "xsec_token=([A-Za-z0-9_\\-%=]+)",
                Pattern.CASE_INSENSITIVE
        ).matcher(html);
        if (!matcher.find()) {
            return null;
        }
        String token = matcher.group(1);
        return "xsec_token=" + token + "&xsec_source=pc_share";
    }

    private MediaInfo extractFromInitialState(String html) {
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

    private static boolean isEmptyNoteDetail(JsonNode root) {
        JsonNode map = root.path("note").path("noteDetailMap");
        if (map.isMissingNode()) {
            map = root.path("noteDetailMap");
        }
        return map.isMissingNode() || map.isNull() || (map.isObject() && map.isEmpty());
    }

    private MediaInfo findNoteVideo(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        MediaInfo preferred = fromPreferredNotePaths(node);
        if (preferred != null) {
            return preferred;
        }
        if (isEmptyNoteDetail(node)) {
            return null;
        }
        return deepFindNoteNode(node, 0);
    }

    private MediaInfo fromPreferredNotePaths(JsonNode root) {
        if (root == null || !root.isObject()) {
            return null;
        }
        MediaInfo info = fromNoteDetailMap(root.path("note").path("noteDetailMap"));
        if (info != null) {
            return info;
        }
        info = fromNoteDetailMap(root.path("noteDetailMap"));
        if (info != null) {
            return info;
        }
        info = fromNoteNode(root.path("noteData").path("data").path("noteData"));
        if (usable(info)) {
            return info;
        }
        info = fromNoteNode(root.path("note").path("noteData").path("data").path("noteData"));
        if (usable(info)) {
            return info;
        }
        return null;
    }

    private MediaInfo fromNoteDetailMap(JsonNode map) {
        if (map == null || !map.isObject() || map.isEmpty()) {
            return null;
        }
        Iterator<JsonNode> values = map.elements();
        while (values.hasNext()) {
            JsonNode entry = values.next();
            MediaInfo info = fromNoteNode(entry.path("note"));
            if (usable(info)) {
                return info;
            }
            info = fromNoteNode(entry);
            if (usable(info)) {
                return info;
            }
        }
        return null;
    }

    private MediaInfo deepFindNoteNode(JsonNode node, int depth) {
        if (node == null || node.isMissingNode() || node.isNull() || depth > 12) {
            return null;
        }
        if (node.isObject()) {
            // 跳过推荐流，防止短链落到首页时误解析别人的笔记
            if (node.has("noteCard") || node.has("feeds") || "noteCard".equals(text(node, "modelType"))) {
                return null;
            }
            boolean looksLikeNote = node.has("title") || node.has("desc") || node.has("user")
                    || node.has("noteId") || (node.has("id") && (node.has("imageList") || node.has("video")));
            if (looksLikeNote && (node.has("video") || node.has("imageList") || node.has("image_list"))) {
                MediaInfo info = fromNoteNode(node);
                if (usable(info)) {
                    return info;
                }
            }
            if (node.has("noteDetailMap")) {
                MediaInfo info = fromNoteDetailMap(node.get("noteDetailMap"));
                if (info != null) {
                    return info;
                }
            }
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey();
                if ("feeds".equals(key) || "noteCard".equals(key) || "recommendVideoMap".equals(key)) {
                    continue;
                }
                MediaInfo nested = deepFindNoteNode(entry.getValue(), depth + 1);
                if (nested != null) {
                    return nested;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                MediaInfo nested = deepFindNoteNode(child, depth + 1);
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
            if (!hasText(videoUrl)) {
                videoUrl = firstUrl(video.path("consumer").path("originVideoKey"));
            }
            if (!hasText(videoUrl)) {
                JsonNode stream = video.path("media").path("stream");
                videoUrl = deepFirstMp4(stream);
            }
        }

        if (!hasText(videoUrl)) {
            videoUrl = deepFirstMp4(note.path("video"));
        }

        List<String> imageUrls = extractImageUrls(note);
        // noteCard.cover
        if (imageUrls.isEmpty()) {
            String coverUrl = firstNonBlank(
                    text(note.path("cover"), "urlDefault"),
                    text(note.path("cover"), "url"),
                    deepFirstImage(note.path("cover"))
            );
            if (hasText(coverUrl)) {
                imageUrls.add(normalizeUrl(coverUrl));
            }
        }

        String cover = firstNonBlank(
                imageUrls.isEmpty() ? null : imageUrls.get(0),
                toWatermarkFreeImageUrl(null, null, text(note.path("imageList").path(0), "urlDefault")),
                toWatermarkFreeImageUrl(null, null, text(note.path("imageList").path(0), "url")),
                toWatermarkFreeImageUrl(null, null, text(note.path("cover"), "urlDefault")),
                toWatermarkFreeImageUrl(null, null, text(note.path("cover"), "url")),
                text(video.path("image"), "firstFrameFileid"),
                deepFirstImage(note)
        );
        if (hasText(cover)) {
            cover = firstNonBlank(toWatermarkFreeImageUrl(null, null, cover), cover);
        }

        if (!hasText(videoUrl) && imageUrls.isEmpty()) {
            return null;
        }

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
            String raw = firstNonBlank(
                    text(img, "urlDefault"),
                    text(img, "url"),
                    preferInfoListUrl(img.path("infoList")),
                    preferInfoListUrl(img.path("info_list")),
                    firstUrl(img.path("urlList")),
                    firstUrl(img.path("url_list")),
                    deepFirstImage(img)
            );
            String free = toWatermarkFreeImageUrl(
                    text(img, "traceId"),
                    text(img, "fileId"),
                    raw
            );
            String url = firstNonBlank(free, raw);
            if (hasText(url)) {
                out.add(normalizeUrl(url));
            }
        }
    }

    private MediaInfo extractByRegex(String html, String noteId) {
        if (html == null || !hasText(noteId)) {
            return null;
        }
        // 发现页/首页 noteDetailMap 为空时，HTML 里会有推荐流图片，不能当成本笔记
        if (html.contains("noteDetailMap\":{}") || html.contains("noteDetailMap\": {}")) {
            return null;
        }
        if (!html.contains(noteId)) {
            return null;
        }
        String normalizedHtml = unescapeJsonUrl(html);

        Matcher videoMatcher = VIDEO_URL.matcher(normalizedHtml);
        if (videoMatcher.find()) {
            String url = normalizeUrl(videoMatcher.group());
            if (isLikelyMediaUrl(url)) {
                return MediaInfo.builder()
                        .platform(Platform.XIAOHONGSHU)
                        .mediaType(MediaInfo.TYPE_VIDEO)
                        .title(noteId != null ? "小红书笔记 " + noteId : "小红书笔记")
                        .author("")
                        .videoUrl(url)
                        .build();
            }
        }

        LinkedHashSet<String> images = new LinkedHashSet<>();
        Matcher imageMatcher = IMAGE_URL.matcher(normalizedHtml);
        while (imageMatcher.find()) {
            String url = normalizeUrl(imageMatcher.group());
            if (!isLikelyMediaUrl(url)) {
                continue;
            }
            if (url.contains("nd_prv") || url.contains("WB_PRV") || url.contains("webp_prv")) {
                continue;
            }
            String free = toWatermarkFreeImageUrl(null, null, url);
            images.add(hasText(free) ? free : url);
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
            // ND/WB_DFT 是带处理后缀的展示图，后续会转无水印；仍可作为 token 来源
            if ("WB_DFT".equalsIgnoreCase(scene) || "ND_DFT".equalsIgnoreCase(scene)) {
                return url;
            }
            if (fallback == null) {
                fallback = url;
            }
        }
        return fallback;
    }

    /**
     * 将 sns-webpic 带 !nd_dft / !nc_n 等水印处理后缀的地址，转为 ci.xiaohongshu.com 原图。
     * 参考社区常用做法：截取 1040g…（可含 notes_pre_post/spectrum 前缀）再拼 imageView2。
     */
    private static String toWatermarkFreeImageUrl(String traceId, String fileId, String rawUrl) {
        String token = extractImageToken(traceId, fileId, rawUrl);
        if (!hasText(token)) {
            return stripProcessingSuffix(rawUrl);
        }
        return "https://ci.xiaohongshu.com/" + token + "?imageView2/2/w/format/jpg";
    }

    private static String extractImageToken(String traceId, String fileId, String rawUrl) {
        String fromUrl = extractTokenFromWebPicUrl(rawUrl);
        if (hasText(fromUrl)) {
            return fromUrl;
        }
        String fromTrace = normalizeTokenCandidate(traceId);
        if (hasText(fromTrace)) {
            return fromTrace;
        }
        String fromFile = normalizeTokenCandidate(fileId);
        if (hasText(fromFile)) {
            return fromFile;
        }
        if (!hasText(rawUrl)) {
            return null;
        }
        Matcher matcher = TOKEN_1040.matcher(rawUrl);
        if (matcher.find()) {
            String prefix = matcher.group(1);
            String id = matcher.group(2);
            return hasText(prefix) ? prefix + id : id;
        }
        return null;
    }

    private static String extractTokenFromWebPicUrl(String rawUrl) {
        if (!hasText(rawUrl)) {
            return null;
        }
        String normalized = unescapeJsonUrl(rawUrl);
        Matcher matcher = WEB_PIC_TOKEN.matcher(normalized);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static String normalizeTokenCandidate(String value) {
        if (!hasText(value)) {
            return null;
        }
        String v = value.trim();
        if (v.contains("/")) {
            // spectrum/xxx 或 notes_pre_post/xxx —— 保留有意义前缀
            Matcher matcher = TOKEN_1040.matcher(v);
            if (matcher.find()) {
                String prefix = matcher.group(1);
                String id = matcher.group(2);
                return hasText(prefix) ? prefix + id : id;
            }
            int slash = v.lastIndexOf('/');
            return slash >= 0 && slash < v.length() - 1 ? v.substring(slash + 1) : v;
        }
        return v;
    }

    /** 去掉 !nd_dft_… 一类处理后缀，得到同域近似原图（弱兜底） */
    private static String stripProcessingSuffix(String rawUrl) {
        if (!hasText(rawUrl) || !rawUrl.contains("!")) {
            return null;
        }
        String normalized = normalizeUrl(rawUrl);
        int bang = normalized.indexOf('!');
        if (bang <= 0) {
            return null;
        }
        return normalized.substring(0, bang);
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
            if (v.startsWith("http") && (v.contains("sns-webpic") || v.contains("xhscdn")
                    || v.contains(".jpg") || v.contains(".webp") || v.contains(".jpeg") || v.contains("ci.xiaohongshu"))) {
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
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(0, i + 1);
                }
            }
        }
        return text;
    }

    private static String unescapeJsonUrl(String html) {
        return html.replace("\\u002F", "/")
                .replace("\\/", "/")
                .replace("\\\"", "\"");
    }

    private static String normalizeUrl(String url) {
        if (url == null) {
            return null;
        }
        String normalized = url.replace("\\u002F", "/")
                .replace("\\/", "/")
                .replace("&amp;", "&")
                .replace("\"", "");
        if (normalized.startsWith("http://sns-") || normalized.startsWith("http://ci.xiaohongshu.com")) {
            normalized = "https://" + normalized.substring("http://".length());
        }
        // 去掉尾部 JSON 残留
        int cut = indexOfAny(normalized, ' ', '\'', '"', '}', ']', '<');
        if (cut > 0) {
            normalized = normalized.substring(0, cut);
        }
        return normalized;
    }

    private static int indexOfAny(String s, char... chars) {
        int min = -1;
        for (char c : chars) {
            int i = s.indexOf(c);
            if (i >= 0 && (min < 0 || i < min)) {
                min = i;
            }
        }
        return min;
    }

    private static boolean isLikelyMediaUrl(String url) {
        if (!hasText(url) || url.length() < 20) {
            return false;
        }
        String lower = url.toLowerCase();
        if (lower.contains("fe-static.xhscdn.com") || lower.contains("/formula-static/")) {
            return false;
        }
        return lower.contains("sns-webpic")
                || lower.contains("sns-video")
                || lower.contains("ci.xiaohongshu.com")
                || lower.contains(".mp4")
                || lower.contains(".mov")
                || lower.contains("webp")
                || lower.contains("jpg")
                || lower.contains("jpeg")
                || lower.contains("png")
                || lower.contains("notes_")
                || lower.contains("spectrum");
    }

    private static boolean usable(MediaInfo info) {
        return info != null && (info.hasVideo() || info.hasImages());
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

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }
}
