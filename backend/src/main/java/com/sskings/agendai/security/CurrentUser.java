package com.sskings.agendai.security;

import com.sskings.agendai.common.ApiException;
import com.sskings.agendai.user.User;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

/** O usuário da requisição vem SEMPRE do token validado — nunca de um id no corpo ou na URL. */
public final class CurrentUser {

    private CurrentUser() {}

    public static User get() {
        return find().orElseThrow(() -> ApiException.unauthorized("Authentication required"));
    }

    public static Optional<UUID> id() {
        return find().map(User::getId);
    }

    private static Optional<User> find() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof User user ? Optional.of(user) : Optional.empty();
    }
}
