package sn.coophabitat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import sn.coophabitat.security.CoopJwtAuthenticationConverter;
import sn.coophabitat.security.JwtService;
import sn.coophabitat.security.RateLimitFilter;
import sn.coophabitat.security.RateLimiter;

import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    public static final String[] PUBLIC_ENDPOINTS = {
            "/api/v1/public/**",
            "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout",
            "/api/v1/auth/password-reset/**", "/api/v1/auth/invitation/**",
            "/api/v1/payments/webhooks/**",
            "/actuator/health", "/actuator/health/**"
    };

    private static final String[] SWAGGER = {"/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**"};

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, CoopJwtAuthenticationConverter converter, JwtService jwtService,
                                    RateLimiter rateLimiter, AppProperties props) throws Exception {
        AuthenticationEntryPoint entryPoint = (req, res, ex) -> writeJson(res, HttpStatus.UNAUTHORIZED,
                "NON_AUTHENTIFIE", "Authentification requise ou session expirée");
        AccessDeniedHandler deniedHandler = (req, res, ex) -> writeJson(res, HttpStatus.FORBIDDEN,
                "ACCES_REFUSE", "Vous n'avez pas les droits nécessaires pour cette opération");

        http
                // API sans état authentifiée par jeton Bearer : la protection CSRF classique n'est pas applicable.
                // Les seules routes lisant un cookie (refresh, logout) exigent l'en-tête X-Requested-With
                // et un cookie SameSite=Strict (voir AuthController).
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource(props)))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                                        + "script-src 'self'; frame-ancestors 'none'; object-src 'none'"))
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000)))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(PUBLIC_ENDPOINTS).permitAll();
                    if (props.api().swaggerEnabled()) {
                        auth.requestMatchers(SWAGGER).permitAll();
                    } else {
                        auth.requestMatchers(SWAGGER).denyAll();
                    }
                    auth.requestMatchers("/actuator/**").hasAuthority("SETTINGS_ADMIN");
                    auth.requestMatchers("/api/**").authenticated();
                    auth.anyRequest().denyAll();
                })
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.decoder(jwtService.decoder()).jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler))
                .addFilterBefore(new RateLimitFilter(rateLimiter), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    private static void writeJson(jakarta.servlet.http.HttpServletResponse res, HttpStatus status, String code,
                                  String message) throws java.io.IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write("{\"status\":" + status.value() + ",\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    CorsConfigurationSource corsConfigurationSource(AppProperties props) {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = props.cors().allowedOrigins() == null ? List.of() : props.cors().allowedOrigins();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Requested-With"));
        config.setExposedHeaders(List.of("Content-Disposition"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    /** Argon2id, paramètres recommandés par l'OWASP (m = 19 Mio, t = 2, p = 1). */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
    }
}
