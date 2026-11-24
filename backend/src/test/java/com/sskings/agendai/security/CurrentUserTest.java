package com.sskings.agendai.security;

import com.sskings.agendai.common.ApiException;
import com.sskings.agendai.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentUserTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Usuário vem só do token validado: sem autenticação ou com outro tipo de principal → ninguém (401 ao exigir)")
    void currentUser() {
        assertThat(CurrentUser.id()).isEmpty();
        assertThatThrownBy(CurrentUser::get).isInstanceOf(ApiException.class).hasMessage("Authentication required");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("texto", null));
        assertThat(CurrentUser.id()).isEmpty();
        User ana = new User("Ana", "ana@x.com", "hash", Instant.now());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(ana, null));
        assertThat(CurrentUser.get()).isSameAs(ana);
        assertThat(CurrentUser.id()).contains(ana.getId());
    }
}
