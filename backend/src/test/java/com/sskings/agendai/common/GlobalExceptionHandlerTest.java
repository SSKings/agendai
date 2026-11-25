package com.sskings.agendai.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("Erro inesperado → 500 genérico: nada da exceção (mensagem, classe, SQL) chega ao cliente")
    void unexpectedDoesNotLeak() {
        var r = handler.unexpected(new IllegalStateException("SELECT * FROM users WHERE senha = 'x' at com.sskings.Foo"));
        assertThat(r.getStatusCode().value()).isEqualTo(500);
        assertThat(r.getBody().message()).isEqualTo("Internal error");
        assertThat(r.getBody().toString()).doesNotContain("SELECT", "IllegalState", "com.sskings", "senha");
    }


    @Test
    @DisplayName("Conflito de integridade no banco → 409 genérico, sem nome de tabela nem de restrição")
    void conflict() {
        var r = handler.conflict(new org.springframework.dao.DataIntegrityViolationException("duplicate key value violates unique constraint ux_users_email",
                new java.sql.SQLException("ux_users_email")));
        assertThat(r.getStatusCode().value()).isEqualTo(409);
        assertThat(r.getBody().message()).isEqualTo("Conflict with existing data");
        assertThat(r.getBody().toString()).doesNotContain("ux_users_email", "constraint");
    }

    @Test
    @DisplayName("Parâmetro inválido em rota (validação do método) → 400 'Validation failed'")
    void methodValidation() {
        var r = handler.methodValidation(org.mockito.Mockito.mock(org.springframework.web.method.annotation.HandlerMethodValidationException.class));
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(r.getBody().message()).isEqualTo("Validation failed");
    }
}
