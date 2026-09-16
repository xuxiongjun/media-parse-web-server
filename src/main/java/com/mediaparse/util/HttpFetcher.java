package com.mediaparse.util;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class HttpFetcher {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;

    public HttpFetcher(OkHttpClient client) {
        this.client = client;
    }

    public FetchResult get(String url, String userAgent, String cookie, Map<String, String> extraHeaders) throws IOException {
        return execute(client, buildGet(url, userAgent, cookie, extraHeaders));
    }

    /**
     * 带独立 CookieJar 的 GET：跟随短链跳转时自动携带 Set-Cookie。
     * 每次调用新建会话，避免多用户 Cookie 互相污染。
     */
    public FetchResult getWithSession(String url, String userAgent, String cookie, Map<String, String> extraHeaders)
            throws IOException {
        OkHttpClient session = client.newBuilder()
                .cookieJar(new InMemoryCookieJar())
                .build();
        return execute(session, buildGet(url, userAgent, cookie, extraHeaders));
    }

    /** 同一会话内连续请求（共享 CookieJar）。 */
    public SessionClient openSession() {
        return new SessionClient(client.newBuilder().cookieJar(new InMemoryCookieJar()).build());
    }

    public FetchResult postJson(String url, String jsonBody, String userAgent, String cookie, Map<String, String> extraHeaders)
            throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(jsonBody, JSON))
                .header("User-Agent", userAgent)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json,*/*");
        applyCookieAndExtras(builder, cookie, extraHeaders);
        return execute(client, builder.build());
    }

    private static Request buildGet(String url, String userAgent, String cookie, Map<String, String> extraHeaders) {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Cache-Control", "no-cache");
        applyCookieAndExtras(builder, cookie, extraHeaders);
        return builder.build();
    }

    private static void applyCookieAndExtras(Request.Builder builder, String cookie, Map<String, String> extraHeaders) {
        if (cookie != null && !cookie.isBlank()) {
            builder.header("Cookie", cookie);
        }
        if (extraHeaders != null) {
            extraHeaders.forEach(builder::header);
        }
    }

    private static FetchResult execute(OkHttpClient httpClient, Request request) throws IOException {
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            String text = body != null ? body.string() : "";
            String finalUrl = response.request().url().toString();
            List<String> setCookies = new ArrayList<>();
            List<String> values = response.headers("Set-Cookie");
            if (values != null) {
                setCookies.addAll(values);
            }
            return new FetchResult(response.code(), finalUrl, text, response.header("Content-Type"), setCookies);
        }
    }

    public final class SessionClient {
        private final OkHttpClient session;

        private SessionClient(OkHttpClient session) {
            this.session = session;
        }

        public FetchResult get(String url, String userAgent, String cookie, Map<String, String> extraHeaders)
                throws IOException {
            return execute(session, buildGet(url, userAgent, cookie, extraHeaders));
        }
    }

    public record FetchResult(int code, String finalUrl, String body, String contentType, List<String> setCookies) {
        public String firstCookiePair(String name) {
            if (setCookies == null) {
                return null;
            }
            String prefix = name + "=";
            for (String raw : setCookies) {
                if (raw != null && raw.startsWith(prefix)) {
                    int end = raw.indexOf(';');
                    return end > 0 ? raw.substring(0, end) : raw;
                }
            }
            return null;
        }
    }
}
