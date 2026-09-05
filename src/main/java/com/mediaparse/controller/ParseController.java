package com.mediaparse.controller;

import com.mediaparse.dto.ParseRequest;
import com.mediaparse.dto.ParseResponse;
import com.mediaparse.service.ParseService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ParseController {

    private final ParseService parseService;

    public ParseController(ParseService parseService) {
        this.parseService = parseService;
    }

    @PostMapping("/parse")
    public ParseResponse parse(@Valid @RequestBody ParseRequest request, HttpServletRequest servletRequest) {
        return parseService.parse(request.getUrl(), clientIp(servletRequest));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
