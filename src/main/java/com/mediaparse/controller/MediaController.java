package com.mediaparse.controller;

import com.mediaparse.service.MediaProxyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api")
public class MediaController {

    private final MediaProxyService mediaProxyService;

    public MediaController(MediaProxyService mediaProxyService) {
        this.mediaProxyService = mediaProxyService;
    }

    @GetMapping("/media/{token}")
    public ResponseEntity<StreamingResponseBody> media(
            @PathVariable String token,
            @RequestParam(value = "download", defaultValue = "0") int download,
            @RequestHeader(value = "Range", required = false) String range
    ) {
        MediaProxyService.ProxyResult result = mediaProxyService.proxy(token, range, download == 1);
        return ResponseEntity.status(result.status())
                .headers(result.headers())
                .body(result.body());
    }
}
