package com.mediaparse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MediaParseApplication {

    public static void main(String[] args) {
        SpringApplication.run(MediaParseApplication.class, args);
    }
}
