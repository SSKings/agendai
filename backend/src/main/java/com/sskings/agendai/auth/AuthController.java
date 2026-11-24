package com.sskings.agendai.auth;

import com.sskings.agendai.auth.AuthDtos.AuthResponse;
import com.sskings.agendai.auth.AuthDtos.ChangePasswordRequest;
import com.sskings.agendai.auth.AuthDtos.LoginRequest;
import com.sskings.agendai.auth.AuthDtos.RegisterRequest;
import com.sskings.agendai.common.ApiException;
import com.sskings.agendai.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AuthController {

    /** Cabeçalho exigido no refresh: um formulário de outro site não consegue mandá-lo (defesa extra contra CSRF). */
    static final String CSRF_HEADER = "X-Requested-With";
    static final String CSRF_VALUE = "agendai";

    private final AuthService service;
    private final RefreshCookie cookie;

    @PostMapping("/api/auth/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
        return respond(HttpStatus.CREATED, service.register(req));
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        return respond(HttpStatus.OK, service.login(req));
    }

    @PostMapping("/api/auth/refresh")
    public ResponseEntity<AuthResponse> refresh(@CookieValue(name = RefreshCookie.NAME, required = false) String raw,
                                                @RequestHeader(name = CSRF_HEADER, required = false) String marker) {
        if (!CSRF_VALUE.equals(marker)) throw ApiException.forbidden("Missing " + CSRF_HEADER + " header");
        return respond(HttpStatus.OK, service.refresh(raw));
    }

    @PostMapping("/api/auth/logout")
    public ResponseEntity<Void> logout() {
        service.logout(CurrentUser.get());
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie.cleared()).build();
    }

    @PutMapping("/api/me/password")
    public ResponseEntity<AuthResponse> changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        return respond(HttpStatus.OK, service.changePassword(CurrentUser.get(), req));
    }

    private ResponseEntity<AuthResponse> respond(HttpStatus status, AuthService.Session s) {
        return ResponseEntity.status(status).header(HttpHeaders.SET_COOKIE, cookie.of(s.refreshToken())).body(s.response());
    }
}
