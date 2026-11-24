package com.sskings.agendai.auth;

import com.sskings.agendai.config.AgendaiProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Cookie do refresh token (ADR-005): HttpOnly (JavaScript não lê), Secure, SameSite=Strict, só em /api/auth. */
@Component
class RefreshCookie {

    static final String NAME = "agendai_refresh";
    static final String PATH = "/api/auth";

    private final boolean secure;
    private final Duration maxAge;

    RefreshCookie(AgendaiProperties props) {
        this.secure = props.refresh().secureCookie();
        this.maxAge = Duration.ofDays(props.refresh().ttlDays());
    }

    String of(String raw) {
        return build(raw, maxAge);
    }

    String cleared() {
        return build("", Duration.ZERO);
    }

    private String build(String value, Duration age) {
        return ResponseCookie.from(NAME, value).httpOnly(true).secure(secure).sameSite("Strict").path(PATH).maxAge(age)
                .build().toString();
    }
}
