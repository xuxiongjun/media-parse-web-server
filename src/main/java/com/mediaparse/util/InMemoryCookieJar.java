package com.mediaparse.util;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 单次解析会话用的 Cookie 罐。小红书短链跳转依赖中间响应的 Set-Cookie（如 acw_tc），
 * 不能用全局共享 CookieJar，避免串号。
 */
public final class InMemoryCookieJar implements CookieJar {

    private final List<Cookie> cookies = new CopyOnWriteArrayList<>();

    @Override
    public void saveFromResponse(HttpUrl url, List<Cookie> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return;
        }
        for (Cookie cookie : incoming) {
            cookies.removeIf(existing -> sameIdentity(existing, cookie));
            cookies.add(cookie);
        }
    }

    @Override
    public List<Cookie> loadForRequest(HttpUrl url) {
        if (url == null) {
            return List.of();
        }
        List<Cookie> matched = new ArrayList<>();
        for (Cookie cookie : cookies) {
            if (cookie.matches(url)) {
                matched.add(cookie);
            }
        }
        return matched;
    }

    private static boolean sameIdentity(Cookie a, Cookie b) {
        return a.name().equals(b.name())
                && a.domain().equals(b.domain())
                && a.path().equals(b.path());
    }
}
