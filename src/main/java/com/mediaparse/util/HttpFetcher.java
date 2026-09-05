package com.mediaparse.util;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

@Component
public class HttpFetcher {

    private final OkHttpClient client;

    public HttpFetcher(OkHttpClient client) {
        this.client = client;
    }

    public FetchResult get(String url, String userAgent, String cookie, Map<String, String> extraHeaders) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        if (cookie != null && !cookie.isBlank()) {
            builder.header("Cookie", cookie);
        }
        if (extraHeaders != null) {
            extraHeaders.forEach(builder::header);
        }
        try (Response response = client.newCall(builder.get().build()).execute()) {
            ResponseBody body = response.body();
            String text = body != null ? body.string() : "";
            String finalUrl = response.request().url().toString();
            return new FetchResult(response.code(), finalUrl, text, response.header("Content-Type"));
        }
    }

    public record FetchResult(int code, String finalUrl, String body, String contentType) {
    }
}
