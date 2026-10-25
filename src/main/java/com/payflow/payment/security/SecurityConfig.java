package com.payflow.payment.security;

import com.payflow.payment.dto.ErrorResponse;
import com.payflow.payment.exception.ErrorCode;
import com.payflow.payment.exception.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

/**
 * Stateless bearer-token security. URL rules give coarse role checks;
 * ownership is enforced in the services with an explicit {@link Caller}.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig implements WebMvcConfigurer {

    private static final int MIN_HMAC_SECRET_BYTES = 32;

    private final CallerArgumentResolver callerArgumentResolver;

    public SecurityConfig(CallerArgumentResolver callerArgumentResolver) {
        this.callerArgumentResolver = callerArgumentResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(callerArgumentResolver);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper jsonMapper)
            throws Exception {
        String admin = Caller.ADMIN_AUTHORITY;
        http
                .csrf(csrf -> csrf.disable()) // bearer tokens only; no cookies to forge
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").hasAuthority(admin)
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasAuthority(admin)
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/deposits", "/api/v1/withdrawals").hasAuthority(admin)
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/accounts/*/status")
                                .hasAuthority(admin)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/accounts/*/owner")
                                .hasAuthority(admin)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> {})
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setHeader("WWW-Authenticate", "Bearer");
                            write(jsonMapper, request, response, ErrorCode.UNAUTHENTICATED,
                                    "A valid bearer token is required");
                        })
                        .accessDeniedHandler((request, response, exception) ->
                                write(jsonMapper, request, response, ErrorCode.ACCESS_DENIED,
                                        "The caller is not permitted to perform this action")))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setHeader("WWW-Authenticate", "Bearer");
                            write(jsonMapper, request, response, ErrorCode.UNAUTHENTICATED,
                                    "A valid bearer token is required");
                        })
                        .accessDeniedHandler((request, response, exception) ->
                                write(jsonMapper, request, response, ErrorCode.ACCESS_DENIED,
                                        "The caller is not permitted to perform this action")));
        return http.build();
    }

    /**
     * Validates signature, {@code exp}/{@code nbf} (60s skew), {@code iss},
     * and {@code aud}. Prefers the identity provider's JWKS; falls back to a
     * shared HS256 secret for local development. Startup fails if neither is
     * configured.
     */
    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties) {
        if (!StringUtils.hasText(properties.issuer())
                || !StringUtils.hasText(properties.audience())) {
            throw new IllegalStateException(
                    "payflow.security.jwt.issuer and .audience must be configured");
        }

        NimbusJwtDecoder decoder;
        if (StringUtils.hasText(properties.jwkSetUri())) {
            decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        } else if (StringUtils.hasText(properties.hmacSecret())) {
            byte[] secret = properties.hmacSecret().getBytes(StandardCharsets.UTF_8);
            if (secret.length < MIN_HMAC_SECRET_BYTES) {
                throw new IllegalStateException(
                        "PAYFLOW_JWT_SECRET must be at least " + MIN_HMAC_SECRET_BYTES + " bytes");
            }
            decoder = NimbusJwtDecoder
                    .withSecretKey(new SecretKeySpec(secret, "HmacSHA256"))
                    .macAlgorithm(MacAlgorithm.HS256)
                    .build();
        } else {
            throw new IllegalStateException(
                    "Configure PAYFLOW_JWT_JWK_SET_URI (preferred) or PAYFLOW_JWT_SECRET");
        }

        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<Collection<String>>(
                JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(properties.audience()));
        OAuth2TokenValidator<Jwt> subject = new JwtClaimValidator<String>(
                JwtClaimNames.SUB, StringUtils::hasText);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()),
                audience,
                subject));
        return decoder;
    }

    private static void write(
            JsonMapper jsonMapper,
            HttpServletRequest request,
            HttpServletResponse response,
            ErrorCode code,
            String message) throws IOException {
        ErrorResponse body = GlobalExceptionHandler.body(
                code, code.status().value(), message, request, List.of());
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), body);
    }
}
