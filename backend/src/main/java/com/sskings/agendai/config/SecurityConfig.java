package com.sskings.agendai.config;

import com.sskings.agendai.security.JwtAuthFilter;
import com.sskings.agendai.security.JwtService;
import com.sskings.agendai.security.RateLimitFilter;
import com.sskings.agendai.security.RequestSizeLimitFilter;
import com.sskings.agendai.user.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.time.Clock;
import java.util.List;

/**
 * API sem estado: access token no cabeçalho Authorization; o único cookie é o do refresh (SameSite=Strict, só em
 * /api/auth, e o refresh exige um cabeçalho próprio — ADR-005). Nada de sessão no servidor. Tudo exige login, menos
 * cadastro, login e o health check. Respostas da API com CSP "nada carrega", sem cache e sem referrer.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(11);
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http, JwtService jwt, UserRepository users, AgendaiProperties props, Clock clock,
                            StringRedisTemplate redis,
                            @Value("${springdoc.swagger-ui.enabled:false}") boolean docs) throws Exception {
        PathPatternRequestMatcher.Builder path = PathPatternRequestMatcher.withDefaults();
        return http
                // Sem CSRF do Spring: a API autentica por Authorization (o navegador não manda sozinho); a única rota com
                // cookie (refresh) exige SameSite=Strict + X-Requested-With (docs/seguranca.md, linha CSRF)
                .csrf(c -> c.disable())
                .cors(Customizer.withDefaults())             // bean "corsConfigurationSource" abaixo
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .headers(h -> h
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(path.matcher("/api/**"),
                                new StaticHeadersWriter("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> write(res, 401, "Authentication required"))
                        .accessDeniedHandler((req, res, ex) -> write(res, 403, "Access denied")))
                .authorizeHttpRequests(a -> {
                    a.requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login", "/api/auth/refresh").permitAll()
                     .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                     .requestMatchers("/error").permitAll();
                    if (docs) a.requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll();
                    a.anyRequest().authenticated();
                })
                .addFilterBefore(new RequestSizeLimitFilter(props.maxRequestBytes()), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new RateLimitFilter(props.rateLimit().authPerMinute(), redis, clock), UsernamePasswordAuthenticationFilter.class)
                // criado aqui (e não como @Bean): só roda dentro da cadeia de segurança, nunca registrado de novo no
                // servidor como filtro solto — o que também o executaria antes de o banco estar pronto
                .addFilterBefore(new JwtAuthFilter(jwt, users), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /** CORS só para as origens configuradas (o front); nunca "*". */
    @Bean
    CorsConfigurationSource corsConfigurationSource(AgendaiProperties props) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(props.cors().allowedOrigins());
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
        c.setAllowCredentials(true);                  // o cookie do refresh (HttpOnly, SameSite=Strict) — ADR-005
        c.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", c);
        return source;
    }

    private static void write(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.getWriter().write("{\"status\":" + status + ",\"message\":\"" + message + "\"}");
    }
}
