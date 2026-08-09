package com.jazzlogs.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "supabaseJwt";

    // Every route except /public/** requires a Supabase JWT (see
    // SecurityConfig) — declaring the scheme here means Postman/Swagger UI
    // already know to send it as "Authorization: Bearer <token>" once you
    // paste one in, instead of guessing per-request.
    @Bean
    OpenAPI jazzLogsOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("JazzLogs API")
                .description("Jazz-focused music logging and discovery backend.")
                .version("v1"))
            .components(new Components()
                .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")))
            .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
