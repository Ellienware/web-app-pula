package com.momocircle.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final Environment environment;

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, Environment environment) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.environment = environment;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // spring.h2.console.enabled defaults to false and the "prod" profile block
        // never sets it true, so the H2 console bean itself isn't created in prod
        // regardless of this rule — but permitAll-ing the path unconditionally here
        // is still profile-blind and one config mistake (someone adding
        // h2.console.enabled: true to the shared, non-profile block) away from
        // actually exposing it. Gate the permitAll on the same condition that
        // would need to be true for the console to exist at all, as real
        // defense-in-depth rather than relying solely on the other property.
        boolean isProd = environment.acceptsProfiles(Profiles.of("prod"));
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/api/auth/**", "/actuator/health", "/api/config/**",
                            "/api/momoride/ussd").permitAll();
                    if (!isProd) {
                        auth.requestMatchers("/h2-console/**").permitAll();
                    }
                    auth.requestMatchers(org.springframework.http.HttpMethod.GET,
                                    "/api/businesses", "/api/businesses/*", "/api/businesses/*/products",
                                    "/api/products", "/api/products/*", "/api/reviews", "/api/reviews/summary",
                                    "/api/market/products", "/api/market/products/*", "/api/market/categories",
                                    "/api/stock/products", "/api/stock/products/*",
                                    "/api/stock/suppliers/*", "/api/stock/suppliers/*/products",
                                    "/api/canonical-products", "/api/canonical-products/*",
                                    "/api/canonical-products/*/comparison",
                                    // PULA Food discovery (read-only, no personal data)
                                    "/api/market/food/search", "/api/market/food/canonical/*/compare",
                                    "/api/market/food/items/*/compare", "/api/market/restaurants",
                                    "/api/market/restaurants/*", "/api/market/restaurants/*/menu",
                                    // Branch directory (read-only; active branches and customer-facing details only)
                                    "/api/businesses/*/branch-directory", "/api/businesses/*/branch-directory/*/products",
                                    // Seller-uploaded product / dish / service images (read-only, customer-facing)
                                    "/api/images/*").permitAll()
                            .anyRequest().authenticated();
                })
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(",")).map(String::trim).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
