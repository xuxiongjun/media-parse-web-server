package com.mediaparse.service;

import com.mediaparse.config.AppProperties;
import com.mediaparse.domain.StoredMedia;
import com.mediaparse.dto.FetchImagesResponse;
import com.mediaparse.exception.BusinessException;
import com.mediaparse.util.HttpFetcher;
import com.mediaparse.util.LinkCleaner;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从豆包等 AI 聊天 / 分享页 HTML 中提取图片，经代理 token 返回给前端去水印。
 */
@Service
public class ChatImageFetchService {

    private static final int MAX_IMAGES_PER_LINK = 120;
    private static final int MIN_IMAGE_BYTES = 8_192;
    private static final long MAX_IMAGE_BYTES = 25L * 1024 * 1024;

    private static final String PC_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    /** 正规化后的 JSON 字段 */
    private static final Pattern IMAGE_ORI_RAW_URL = Pattern.compile(
            "\"image_ori_raw\"\\s*:\\s*\\{\\s*\"url\"\\s*:\\s*\"(https[^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );
    /** 有界兜底，禁止 DOTALL，避免大页灾难性回溯 */
    private static final Pattern IMAGE_ORI_RAW_BOUNDED = Pattern.compile(
            "image_ori_raw.{0,80}url.{0,40}\"(https[^\"]{10,800})\"",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern IMAGE_ORI_URL = Pattern.compile(
            "\"image_ori\"\\s*:\\s*\\{\\s*\"url\"\\s*:\\s*\"(https[^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern IMAGE_ID = Pattern.compile(
            "rc_gen_image/([a-f0-9]{32})",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");
    private static final Pattern SHARE_NAME = Pattern.compile(
            "\"share_name\"\\s*:\\s*\"([^\"]{1,120})\"",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CONVERSATION_NAME = Pattern.compile(
            "\"(?:conversation_name|thread_title|chat_title|share_title)\"\\s*:\\s*\"([^\"]{1,120})\"",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TITLE_TAG = Pattern.compile(
            "<title[^>]*>(.*?)</title>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern OG_TITLE = Pattern.compile(
            "<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE
    );

    private final HttpFetcher httpFetcher;
    private final OkHttpClient okHttpClient;
    private final MediaTokenService mediaTokenService;
    private final RateLimitService rateLimitService;
    private final AppProperties appProperties;

    public ChatImageFetchService(HttpFetcher httpFetcher,
                                 OkHttpClient okHttpClient,
                                 MediaTokenService mediaTokenService,
                                 RateLimitService rateLimitService,
                                 AppProperties appProperties) {
        this.httpFetcher = httpFetcher;
        this.okHttpClient = okHttpClient;
        this.mediaTokenService = mediaTokenService;
        this.rateLimitService = rateLimitService;
        this.appProperties = appProperties;
    }

    public FetchImagesResponse fetch(String rawInput, String clientIp) {
        rateLimitService.checkParse(clientIp);

        String url = LinkCleaner.extractUrl(rawInput);
        String platform = detectAiPlatform(url);
        if (platform == null) {
            throw new BusinessException("UNSUPPORTED", "暂支持豆包等 AI 聊天 / 分享链接（如 doubao.com）");
        }

        String ua = "doubao".equals(platform) ? PC_UA : appProperties.getHttp().getUserAgent();
        if (ua == null || ua.isBlank()) {
            ua = PC_UA;
        }

        HttpFetcher.FetchResult page;
        try {
            page = httpFetcher.getWithSession(url, ua, null, Map.of(
                    "Referer", refererFor(platform),
                    "Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8"
            ));
        } catch (Exception ex) {
            throw new BusinessException("PARSE_FAILED", "打开分享页失败：" + safeMessage(ex));
        }

        if (page.code() >= 400) {
            throw new BusinessException("PARSE_FAILED", "分享页返回异常（" + page.code() + "）");
        }

        String html = normalizeHtml(page.body());
        List<String> imageUrls = extractImageUrls(html);
        if (imageUrls.isEmpty()) {
            throw new BusinessException("PARSE_FAILED", "未在页面中找到可下载的图片，请确认链接可公开访问");
        }
        if (imageUrls.size() > MAX_IMAGES_PER_LINK) {
            imageUrls = new ArrayList<>(imageUrls.subList(0, MAX_IMAGES_PER_LINK));
        }

        String title = extractTitle(html);
        if (title == null || title.isBlank()) {
            title = defaultTitle(platform);
        }

        List<String> proxies = new ArrayList<>();
        int idx = 1;
        int failedDownloads = 0;
        for (String imageUrl : imageUrls) {
            byte[] payload;
            try {
                payload = downloadImageBytes(imageUrl, platform);
            } catch (BusinessException ex) {
                failedDownloads++;
                continue;
            }
            String token = mediaTokenService.store(StoredMedia.builder()
                    .url(imageUrl)
                    .payload(payload)
                    .contentTypeHint(guessContentType(imageUrl, payload))
                    .filename(buildFilename(platform, title, idx, payload))
                    .build());
            proxies.add("/api/media/" + token);
            idx++;
        }

        if (proxies.isEmpty()) {
            throw new BusinessException("PARSE_FAILED",
                    failedDownloads > 0
                            ? "找到图片但下载失败（可能被防盗链拦截），请稍后重试"
                            : "未获取到可下载的图片");
        }

        return FetchImagesResponse.builder()
                .platform(platform)
                .title(title)
                .imageProxyUrls(proxies)
                .expireAt(mediaTokenService.expireAtMillis())
                .build();
    }

    private byte[] downloadImageBytes(String imageUrl, String platform) {
        Request.Builder builder = new Request.Builder()
                .url(imageUrl)
                .get()
                .header("User-Agent", PC_UA)
                .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                .header("Referer", refererFor(platform))
                .header("Origin", refererFor(platform).replaceAll("/$", ""));

        try (Response response = okHttpClient.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new BusinessException("PARSE_FAILED", "图片下载失败 (" + response.code() + ")");
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new BusinessException("PARSE_FAILED", "图片内容为空");
            }
            byte[] bytes = body.bytes();
            if (bytes.length < MIN_IMAGE_BYTES) {
                throw new BusinessException("PARSE_FAILED", "图片过小，可能不是原图");
            }
            if (bytes.length > MAX_IMAGE_BYTES) {
                throw new BusinessException("PARSE_FAILED", "图片过大，已跳过");
            }
            if (!looksLikeImage(bytes)) {
                throw new BusinessException("PARSE_FAILED", "下载内容不是有效图片");
            }
            return bytes;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("PARSE_FAILED", "图片下载异常：" + safeMessage(ex));
        }
    }

    private static boolean looksLikeImage(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return false;
        }
        // PNG
        if (bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return true;
        }
        // JPEG
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            return true;
        }
        // WEBP: RIFF....WEBP
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return true;
        }
        // GIF
        if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
            return true;
        }
        return false;
    }

    private static String guessContentType(String url, byte[] payload) {
        if (payload != null && payload.length >= 4) {
            if (payload[0] == (byte) 0x89 && payload[1] == 'P') {
                return "image/png";
            }
            if ((payload[0] & 0xFF) == 0xFF && (payload[1] & 0xFF) == 0xD8) {
                return "image/jpeg";
            }
            if (payload[0] == 'R' && payload[1] == 'I') {
                return "image/webp";
            }
            if (payload[0] == 'G' && payload[1] == 'I') {
                return "image/gif";
            }
        }
        return guessContentType(url);
    }

    private static String buildFilename(String platform, String title, int index, byte[] payload) {
        String ext = ".jpg";
        if (payload != null && payload.length >= 4) {
            if (payload[0] == (byte) 0x89 && payload[1] == 'P') {
                ext = ".png";
            } else if (payload[0] == 'R' && payload[1] == 'I') {
                ext = ".webp";
            } else if (payload[0] == 'G' && payload[1] == 'I') {
                ext = ".gif";
            }
        }
        String base = (title == null || title.isBlank() ? platform : title)
                .replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        if (base.length() > 40) {
            base = base.substring(0, 40);
        }
        return base + "_" + index + ext;
    }

    static String detectAiPlatform(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String lower = url.toLowerCase();
        if (lower.contains("doubao.com") || lower.contains("doubaocdn.com")) {
            return "doubao";
        }
        if (lower.contains("yuanbao.tencent.com") || lower.contains("hunyuan.tencent.com")) {
            return "yuanbao";
        }
        if (lower.contains("jimeng.jianying.com") || lower.contains("dreamina")) {
            return "jimeng";
        }
        if (lower.contains("tongyi.aliyun.com") || lower.contains("qianwen") || lower.contains("tongyi.com")) {
            return "tongyi";
        }
        if (lower.contains("chatgpt.com") || lower.contains("chat.openai.com")) {
            return "chatgpt";
        }
        return null;
    }

    private static String refererFor(String platform) {
        return switch (platform) {
            case "doubao" -> "https://www.doubao.com/";
            case "yuanbao" -> "https://yuanbao.tencent.com/";
            case "jimeng" -> "https://jimeng.jianying.com/";
            case "tongyi" -> "https://tongyi.aliyun.com/";
            case "chatgpt" -> "https://chatgpt.com/";
            default -> "https://www.doubao.com/";
        };
    }

    private static String defaultTitle(String platform) {
        return switch (platform) {
            case "doubao" -> "豆包分享";
            case "yuanbao" -> "元宝分享";
            case "jimeng" -> "即梦分享";
            case "tongyi" -> "通义分享";
            case "chatgpt" -> "ChatGPT 分享";
            default -> "AI 分享";
        };
    }

    /**
     * 豆包分享页把 JSON 嵌进 HTML：&quot; + \\u002F + \\\" 多重转义。
     */
    static String normalizeHtml(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String t = HtmlUtils.htmlUnescape(html);
        for (int i = 0; i < 6; i++) {
            Matcher m = UNICODE_ESCAPE.matcher(t);
            if (!m.find()) {
                break;
            }
            StringBuilder sb = new StringBuilder(t.length());
            m.reset();
            while (m.find()) {
                int code = Integer.parseInt(m.group(1), 16);
                m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) code)));
            }
            m.appendTail(sb);
            t = sb.toString();
        }
        for (int i = 0; i < 6; i++) {
            String nxt = t.replace("\\\"", "\"").replace("\\/", "/");
            if (nxt.equals(t)) {
                break;
            }
            t = nxt;
        }
        return t;
    }

    static List<String> extractImageUrls(String html) {
        Map<String, String> byId = new LinkedHashMap<>();

        collect(IMAGE_ORI_RAW_URL, html, byId, true);
        if (byId.isEmpty()) {
            collect(IMAGE_ORI_RAW_BOUNDED, html, byId, true);
        }
        if (byId.isEmpty()) {
            collect(IMAGE_ORI_URL, html, byId, false);
        }

        List<String> result = new ArrayList<>();
        for (String url : byId.values()) {
            String cleaned = cleanUrl(url);
            if (cleaned == null || isLikelyThumb(cleaned)) {
                continue;
            }
            result.add(cleaned);
        }
        return result;
    }

    private static void collect(Pattern pattern, String html, Map<String, String> out, boolean preferRaw) {
        Matcher matcher = pattern.matcher(html);
        while (matcher.find()) {
            String raw = matcher.group(1);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            if (preferRaw) {
                String lower = raw.toLowerCase();
                if (!(lower.contains("image_raw") || lower.contains("ori_raw"))) {
                    continue;
                }
            }
            String key = imageKey(raw);
            out.putIfAbsent(key, raw);
        }
    }

    private static String imageKey(String url) {
        Matcher m = IMAGE_ID.matcher(url);
        if (m.find()) {
            return m.group(1).toLowerCase();
        }
        return url;
    }

    private static String cleanUrl(String url) {
        if (url == null) {
            return null;
        }
        String u = url.trim();
        if (u.startsWith("//")) {
            u = "https:" + u;
        }
        if (!(u.startsWith("http://") || u.startsWith("https://"))) {
            return null;
        }
        while (u.endsWith("\\") || u.endsWith(")")) {
            u = u.substring(0, u.length() - 1);
        }
        int hash = u.indexOf('#');
        if (hash >= 0) {
            u = u.substring(0, hash);
        }
        return u;
    }

    private static boolean isLikelyThumb(String url) {
        String lower = url.toLowerCase();
        return lower.contains("downsize")
                || lower.contains("image_thumb")
                || lower.contains("_thumb")
                || lower.contains("cthumb")
                || lower.contains("cpreview")
                || lower.contains("preview_sm")
                || (lower.contains("tplv-") && lower.contains("resize"));
    }

    private static String extractTitle(String html) {
        String fromShare = firstMeaningful(SHARE_NAME, html);
        if (fromShare != null) {
            return fromShare;
        }
        String fromConv = firstMeaningful(CONVERSATION_NAME, html);
        if (fromConv != null) {
            return fromConv;
        }
        Matcher og = OG_TITLE.matcher(html);
        if (og.find()) {
            String t = cleanTitle(og.group(1));
            if (t != null) {
                return t;
            }
        }
        Matcher title = TITLE_TAG.matcher(html);
        if (title.find()) {
            return cleanTitle(title.group(1));
        }
        return null;
    }

    private static String firstMeaningful(Pattern pattern, String html) {
        Matcher matcher = pattern.matcher(html);
        while (matcher.find()) {
            String t = cleanTitle(matcher.group(1));
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    /** 过滤空标题、站点名等无区分度文案 */
    private static String cleanTitle(String raw) {
        if (raw == null) {
            return null;
        }
        String t = stripTags(raw).trim()
                .replace("\\n", " ")
                .replaceAll("\\s+", " ");
        if (t.isBlank()) {
            return null;
        }
        String lower = t.toLowerCase();
        if (lower.equals("doubao")
                || lower.equals("豆包")
                || lower.equals("豆包分享")
                || lower.equals("豆包 ai")
                || lower.startsWith("豆包 -")
                || lower.startsWith("doubao -")) {
            return null;
        }
        if (t.length() > 60) {
            t = t.substring(0, 60);
        }
        return t;
    }

    private static String stripTags(String text) {
        return text == null ? "" : text.replaceAll("<[^>]+>", "").replace("&nbsp;", " ").trim();
    }

    private static String guessContentType(String url) {
        String lower = url.toLowerCase();
        if (lower.contains(".png") || lower.contains("image_raw.png")) {
            return "image/png";
        }
        if (lower.contains(".webp")) {
            return "image/webp";
        }
        if (lower.contains(".gif")) {
            return "image/gif";
        }
        return "image/jpeg";
    }

    private static String buildFilename(String platform, String title, int index) {
        return buildFilename(platform, title, index, null);
    }

    private static String safeMessage(Exception ex) {
        String msg = ex.getMessage();
        if (msg == null || msg.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return msg.length() > 120 ? msg.substring(0, 120) : msg;
    }
}
