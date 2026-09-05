package com.mediaparse.service;

import com.mediaparse.config.AppProperties;
import com.mediaparse.domain.StoredMedia;
import com.mediaparse.exception.BusinessException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.Optional;

@Service
public class MediaProxyService {

    private final MediaTokenService mediaTokenService;
    private final OkHttpClient okHttpClient;
    private final AppProperties appProperties;

    public MediaProxyService(MediaTokenService mediaTokenService,
                             OkHttpClient okHttpClient,
                             AppProperties appProperties) {
        this.mediaTokenService = mediaTokenService;
        this.okHttpClient = okHttpClient;
        this.appProperties = appProperties;
    }

    public ProxyResult proxy(String token, String rangeHeader, boolean download) {
        StoredMedia media = mediaTokenService.get(token);
        if (media == null) {
            throw new BusinessException("NOT_FOUND", "资源不存在或已过期，请重新解析");
        }

        Request.Builder builder = new Request.Builder()
                .url(media.getUrl())
                .header("User-Agent", appProperties.getHttp().getUserAgent())
                .header("Accept", "*/*");

        String referer = guessReferer(media.getUrl());
        if (referer != null) {
            builder.header("Referer", referer);
        }
        if (rangeHeader != null && !rangeHeader.isBlank()) {
            builder.header("Range", rangeHeader);
        }

        try {
            Response response = okHttpClient.newCall(builder.get().build()).execute();
            if (!response.isSuccessful() && response.code() != 206) {
                response.close();
                throw new BusinessException("PARSE_FAILED", "上游媒体获取失败 (" + response.code() + ")");
            }

            ResponseBody body = response.body();
            if (body == null) {
                response.close();
                throw new BusinessException("PARSE_FAILED", "上游媒体内容为空");
            }

            HttpHeaders headers = new HttpHeaders();
            String contentType = Optional.ofNullable(response.header("Content-Type"))
                    .orElse(Optional.ofNullable(media.getContentTypeHint()).orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE));
            headers.set(HttpHeaders.CONTENT_TYPE, contentType);

            String contentLength = response.header("Content-Length");
            if (contentLength != null) {
                headers.set(HttpHeaders.CONTENT_LENGTH, contentLength);
            }
            String contentRange = response.header("Content-Range");
            if (contentRange != null) {
                headers.set(HttpHeaders.CONTENT_RANGE, contentRange);
            }
            String acceptRanges = response.header("Accept-Ranges");
            if (acceptRanges != null) {
                headers.set(HttpHeaders.ACCEPT_RANGES, acceptRanges);
            } else {
                headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
            }

            if (download) {
                String filename = media.getFilename() != null ? media.getFilename() : "video.mp4";
                headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
            } else {
                headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline");
            }

            HttpStatus status = response.code() == 206 ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK;
            InputStream upstream = body.byteStream();
            StreamingResponseBody stream = outputStream -> copyAndClose(upstream, outputStream, response);

            return new ProxyResult(status, headers, stream);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("PARSE_FAILED", "媒体代理失败：" + ex.getMessage());
        }
    }

    private static void copyAndClose(InputStream in, OutputStream out, Response response) {
        try (in; response) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            out.flush();
        } catch (Exception ignored) {
            // client aborted / upstream closed
        }
    }

    private static String guessReferer(String mediaUrl) {
        try {
            URI uri = URI.create(mediaUrl);
            String host = uri.getHost();
            if (host == null) {
                return null;
            }
            if (host.contains("douyin") || host.contains("byte") || host.contains("tiktok")) {
                return "https://www.douyin.com/";
            }
            if (host.contains("xiaohongshu") || host.contains("xhscdn") || host.contains("xhs")) {
                return "https://www.xiaohongshu.com/";
            }
            return uri.getScheme() + "://" + host + "/";
        } catch (Exception e) {
            return null;
        }
    }

    public record ProxyResult(HttpStatus status, HttpHeaders headers, StreamingResponseBody body) {
    }
}
