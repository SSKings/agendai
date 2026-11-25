package com.sskings.agendai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Tudo que é configurável do Agendaí (prefixo "agendai" no application.yml). */
@ConfigurationProperties(prefix = "agendai")
public record AgendaiProperties(Jwt jwt, Refresh refresh, Login login, RateLimit rateLimit, long maxRequestBytes, Cors cors) {

    /** Access token: curto (ADR-005), só na memória do front. */
    public record Jwt(String secret, long accessMinutes) {}

    /** Refresh token: cookie HttpOnly de uso único, com rotação. secureCookie=false só em dev (http://localhost). */
    public record Refresh(int ttlDays, boolean secureCookie) {}

    public record Login(int maxFailures, int lockMinutes) {}

    public record RateLimit(int authPerMinute) {}

    public record Cors(List<String> allowedOrigins) {
        public Cors {
            allowedOrigins = allowedOrigins == null ? List.of()
                    : allowedOrigins.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
    }
}
