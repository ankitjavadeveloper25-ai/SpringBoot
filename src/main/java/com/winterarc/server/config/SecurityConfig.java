package com.winterarc.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
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
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtFilter jwtFilter;
    private final boolean h2ConsoleEnabled;
    private final List<String> allowedOrigins;

    public SecurityConfig(JwtFilter jwtFilter,
                          @Value("${spring.h2.console.enabled:false}") boolean h2ConsoleEnabled,
                          @Value("${winterarc.security.cors.allowed-origins:http://localhost:5173,http://localhost:3000,http://127.0.0.1:5173,http://127.0.0.1:3000}") String allowedOriginsStr) {
        this.jwtFilter = jwtFilter;
        this.h2ConsoleEnabled = h2ConsoleEnabled;
        this.allowedOrigins = Arrays.stream(allowedOriginsStr.split(","))
                                    .map(String::trim)
                                    .filter(s -> !s.isEmpty())
                                    .toList();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                // Allow all CORS preflight requests
                auth.requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll();

                auth
                    .requestMatchers("/", "/health", "/favicon.ico").permitAll()
                    .requestMatchers("/api/auth/**").permitAll()
                    .requestMatchers("/api/plans/**").permitAll()
                    .requestMatchers("/api/downloads/**").permitAll()
                    .requestMatchers("/api/webhooks/**").permitAll()
                    .requestMatchers("/api/checkout/razorpay/hosted").permitAll()
                    .requestMatchers("/api/announcements/active").permitAll()
                    .requestMatchers("/error").permitAll();

                // Only permit h2-console if explicitly enabled in configuration (dev/test only)
                if (h2ConsoleEnabled) {
                    auth.requestMatchers("/h2-console/**").permitAll();
                }

                // Strict Admin Endpoints: requires ROLE_ADMIN
                auth.requestMatchers("/api/admin/**").hasAuthority("ROLE_ADMIN");

                auth.anyRequest().authenticated();
            })
            .headers(headers -> {
                headers.contentTypeOptions(ct -> {});
                if (h2ConsoleEnabled) {
                    headers.frameOptions(frame -> frame.disable());
                } else {
                    headers.frameOptions(frame -> frame.deny());
                }
            })
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // Allow all origin patterns (localhost with any port, 127.0.0.1, LAN IP, domain) with credentials
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization", "Content-Disposition"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
