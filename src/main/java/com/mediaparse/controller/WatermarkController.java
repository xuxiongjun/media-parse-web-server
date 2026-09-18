package com.mediaparse.controller;

import com.mediaparse.dto.FetchImagesResponse;
import com.mediaparse.dto.ParseRequest;
import com.mediaparse.service.ChatImageFetchService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/watermark")
public class WatermarkController {

    private final ChatImageFetchService chatImageFetchService;

    public WatermarkController(ChatImageFetchService chatImageFetchService) {
        this.chatImageFetchService = chatImageFetchService;
    }

    /** 从豆包等 AI 聊天 / 分享链接抓取图片，返回代理地址供前端去水印 */
    @PostMapping("/fetch-images")
    public FetchImagesResponse fetchImages(@Valid @RequestBody ParseRequest request,
                                           HttpServletRequest servletRequest) {
        return chatImageFetchService.fetch(request.getUrl(), clientIp(servletRequest));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
