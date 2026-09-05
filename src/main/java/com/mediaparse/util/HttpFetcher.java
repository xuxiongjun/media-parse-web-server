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
        Request.Builder builder = new Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        applyCookieAndExtras(builder, cookie, extraHeaders);
        return execute(builder.build());
    }

    public FetchResult postJson(String url, String jsonBody, String userAgent, String cookie, Map<String, String> extraHeaders) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(jsonBody, JSON))
                .header("User-Agent", userAgent)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json,*/*");
        applyCookieAndExtras(builder, cookie, extraHeaders);
        return execute(builder.build());
    }

    private static void applyCookieAndExtras(Request.Builder builder, String cookie, Map<String, String> extraHeaders) {
        if (cookie != null && !cookie.isBlank()) {
            builder.header("Cookie", cookie);
        }
        if (extraHeaders != null) {
            extraHeaders.forEach(builder::header);
        }
    }

    private FetchResult execute(Request request) throws IOException {
        try (Response response = client.newCall(request).execute()) {
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
