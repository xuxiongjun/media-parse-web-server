package com.mediaparse.config;

import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
public class OkHttpConfig {

    @Bean
    public OkHttpClient okHttpClient(AppProperties props) {
        AppProperties.Http http = props.getHttp();
        return new OkHttpClient.Builder()
                .connectTimeout(http.getConnectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(http.getReadTimeoutMs(), TimeUnit.MILLISECONDS)
                .writeTimeout(http.getReadTimeoutMs(), TimeUnit.MILLISECONDS)
                // 媒体代理会流式传输大文件，不能用短 callTimeout，否则大视频传到一半被掐断
                .callTimeout(Duration.ofMinutes(10))
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .build();
    }
}
