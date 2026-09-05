package com.mediaparse.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mediaparse.config.AppProperties;
import com.mediaparse.domain.StoredMedia;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
public class MediaTokenService {

    private final AppProperties appProperties;
    private Cache<String, StoredMedia> cache;
    private long ttlSeconds;

    public MediaTokenService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @PostConstruct
    public void init() {
        this.ttlSeconds = appProperties.getMediaTokenTtlSeconds();
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .maximumSize(5_000)
                .build();
    }

    public String store(StoredMedia media) {
        String token = UUID.randomUUID().toString().replace("-", "");
        cache.put(token, media);
        return token;
    }

    public StoredMedia get(String token) {
        return cache.getIfPresent(token);
    }

    public long expireAtMillis() {
        return System.currentTimeMillis() + ttlSeconds * 1000;
    }
}
