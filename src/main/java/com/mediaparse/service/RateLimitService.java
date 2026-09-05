package com.mediaparse.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mediaparse.config.AppProperties;
import com.mediaparse.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class RateLimitService {

    private final AppProperties appProperties;
    private Cache<String, AtomicInteger> counter;
    private int limit;

    public RateLimitService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @PostConstruct
    public void init() {
        this.limit = Math.max(1, appProperties.getParseRateLimitPerMinute());
        this.counter = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(1))
                .maximumSize(20_000)
                .build();
    }

    public void checkParse(String clientIp) {
        String key = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp;
        AtomicInteger count = counter.get(key, k -> new AtomicInteger(0));
        while (true) {
            int current = count.get();
            if (current >= limit) {
                throw new BusinessException("RATE_LIMIT", "请求过于频繁，请稍后再试");
            }
            if (count.compareAndSet(current, current + 1)) {
                return;
            }
        }
    }
}
