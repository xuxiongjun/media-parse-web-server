package com.mediaparse.util;

import com.mediaparse.domain.Platform;
import com.mediaparse.exception.BusinessException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LinkCleaner {

    private static final Pattern URL_PATTERN = Pattern.compile(
            "https?://[\\w\\-./?%&=#:+~]+",
            Pattern.CASE_INSENSITIVE
    );

    private LinkCleaner() {
    }

    public static String extractUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("INVALID_PARAM", "请输入分享链接");
        }
        String text = raw.trim();
        if (text.startsWith("http://") || text.startsWith("https://")) {
            return stripTracking(text.split("\\s+")[0]);
        }
        Matcher matcher = URL_PATTERN.matcher(text);
        if (matcher.find()) {
            return stripTracking(matcher.group());
        }
        throw new BusinessException("INVALID_PARAM", "未识别到有效链接，请粘贴完整分享内容");
    }

    private static String stripTracking(String url) {
        int hash = url.indexOf('#');
        if (hash >= 0) {
            url = url.substring(0, hash);
        }
        return url;
    }

    public static Platform detectPlatform(String url) {
        String lower = url.toLowerCase();
        if (lower.contains("douyin.com") || lower.contains("iesdouyin.com") || lower.contains("v.douyin.com")) {
            return Platform.DOUYIN;
        }
        if (lower.contains("xiaohongshu.com") || lower.contains("xhslink.com") || lower.contains("xhscdn.com")) {
            return Platform.XIAOHONGSHU;
        }
        return Platform.UNKNOWN;
    }
}
