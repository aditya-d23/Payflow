package com.payflow.payment.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI payflowOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("PayFlow API")
                        .version("v1")
                        .description("""
                                Accounts, idempotent money transfers, deposits and withdrawals \
                                over a double-entry ledger. Every POST that moves money requires \
                                an Idempotency-Key header; errors use a stable `code` field."""))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
