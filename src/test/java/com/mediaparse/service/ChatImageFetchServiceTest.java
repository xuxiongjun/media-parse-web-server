package com.mediaparse.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatImageFetchServiceTest {

    @Test
    void extractsOriRawFromDoubaoShareHtml() throws Exception {
        Path sample = Path.of("doubao_sample.html");
        if (!Files.exists(sample)) {
            sample = Path.of("doubao_pc.html");
        }
        if (!Files.exists(sample)) {
            return;
        }
        String raw = Files.readString(sample);
        String html = ChatImageFetchService.normalizeHtml(raw);
        List<String> urls = ChatImageFetchService.extractImageUrls(html);
        assertFalse(urls.isEmpty(), "should find images");
        assertTrue(urls.stream().allMatch(u -> u.contains("image_raw") || u.contains("byteimg")),
                "urls should be raw/byteimg: " + urls.get(0));
        System.out.println("extracted " + urls.size() + " -> " + urls.get(0));
    }
}
