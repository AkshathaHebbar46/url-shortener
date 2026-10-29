package com.akshatha.urlshortener.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI shortenerOpenApi(ShortenerProperties properties) {
        return new OpenAPI()
                .info(new Info()
                        .title("URL Shortener API")
                        .version("v1")
                        .description("""
                                Base62 short-code service with Redis-backed read caching, \
                                distributed rate limiting and SSRF-aware URL validation.

                                Errors are RFC 7807 ProblemDetail documents."""))
                .servers(List.of(new Server().url(properties.baseUrl())));
    }
}
