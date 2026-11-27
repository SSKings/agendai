package com.sskings.agendai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionConfigValidatorTest {

    private static final String GOOD_SECRET = "Zx9!k2Lq#v8Rt4Wm6Ny1Pb3Hc7Jd5Fg0Se";

    @Test
    @DisplayName("Configuração de produção correta passa")
    void validPasses() {
        assertThat(ProductionConfigValidator.problems(GOOD_SECRET, List.of("https://agendai.app"), "jdbc:postgresql://db:5432/agendai")).isEmpty();
    }

    @Test
    @DisplayName("Segredo JWT ausente, curto ou de exemplo é recusado")
    void jwtSecret() {
        for (String s : new String[]{null, "", "curto", "a".repeat(40), "change-me-change-me-change-me-change-me",
                "my-super-secret-key-for-jwt-signing-0123456789", "example-key-ABCDEFGHIJKLMNOPQRSTUVWXYZ-0123456789",
                "test-secret-only-for-tests-0123456789-abcdefghijklmnopqrstuvwxyz"}) {
            assertThat(ProductionConfigValidator.problems(s, List.of("https://agendai.app"), "jdbc:postgresql://db/agendai"))
                    .as(String.valueOf(s)).anyMatch(p -> p.startsWith("JWT_SECRET"));
        }
    }

    @Test
    @DisplayName("CORS: obrigatório, só https, nunca curinga")
    void cors() {
        assertThat(ProductionConfigValidator.problems(GOOD_SECRET, null, "jdbc:postgresql://db/agendai"))
                .anyMatch(p -> p.startsWith("CORS_ALLOWED_ORIGINS"));
        for (List<String> origins : List.of(List.<String>of(), List.of("*"), List.of("http://agendai.app"),
                List.of("https://agendai.app", "https://*.app.br"), List.of("http://localhost:5173"))) {
            assertThat(ProductionConfigValidator.problems(GOOD_SECRET, origins, "jdbc:postgresql://db/agendai"))
                    .as(origins.toString()).anyMatch(p -> p.startsWith("CORS_ALLOWED_ORIGINS"));
        }
    }

    @Test
    @DisplayName("Banco: obrigatório e PostgreSQL")
    void database() {
        for (String url : new String[]{null, "", "jdbc:h2:mem:x"}) {
            assertThat(ProductionConfigValidator.problems(GOOD_SECRET, List.of("https://agendai.app"), url))
                    .as(String.valueOf(url)).anyMatch(p -> p.startsWith("DATABASE_URL"));
        }
    }

    @Test
    @DisplayName("Usuário do banco da API: diferente do dono das tabelas (migrações) e do superusuário")
    void databaseUsers() {
        assertThat(ProductionConfigValidator.databaseUserProblems("agendai_app", "agendai_owner")).isEmpty();
        assertThat(ProductionConfigValidator.databaseUserProblems("agendai_owner", "agendai_owner")).anyMatch(p -> p.startsWith("DATABASE_USERNAME"));
        assertThat(ProductionConfigValidator.databaseUserProblems("postgres", "agendai_owner")).anyMatch(p -> p.startsWith("DATABASE_USERNAME"));
        assertThat(ProductionConfigValidator.databaseUserProblems("agendai_app", "")).anyMatch(p -> p.startsWith("DATABASE_MIGRATION_USERNAME"));
        assertThat(ProductionConfigValidator.databaseUserProblems("", "agendai_owner")).anyMatch(p -> p.startsWith("DATABASE_USERNAME"));
        assertThat(ProductionConfigValidator.databaseUserProblems(null, "agendai_owner")).anyMatch(p -> p.startsWith("DATABASE_USERNAME"));
        assertThat(ProductionConfigValidator.databaseUserProblems("agendai_app", null)).anyMatch(p -> p.startsWith("DATABASE_MIGRATION_USERNAME"));
        assertThat(ProductionConfigValidator.databaseUserProblems("agendai_app", "   ")).anyMatch(p -> p.startsWith("DATABASE_MIGRATION_USERNAME"));
    }

    @Test
    @DisplayName("Segredo longo com uma das palavras de exemplo é recusado (cada palavra sozinha)")
    void jwtSecretWords() {
        String base = "Zx9!k2Lq#v8Rt4Wm6Ny1Pb3Hc7Jd5Fg0";
        for (String word : new String[]{"CHANGE", "Test", "secret", "Example"}) {
            assertThat(ProductionConfigValidator.problems(base + word, List.of("https://agendai.app"), "jdbc:postgresql://db/agendai"))
                    .as(word).anyMatch(p -> p.startsWith("JWT_SECRET: parece de exemplo"));
        }
        assertThat(ProductionConfigValidator.problems("ababababababababababababababababab", List.of("https://agendai.app"), "jdbc:postgresql://db/agendai"))
                .as("pouca variedade").anyMatch(p -> p.startsWith("JWT_SECRET: parece de exemplo"));
    }

    @Test
    @DisplayName("Configuração correta: a aplicação sobe")
    void validConfigStarts() {
        new ProductionConfigValidator(props(GOOD_SECRET, List.of("https://agendai.app"), true),
                "jdbc:postgresql://db:5432/agendai", "agendai_app", "agendai_owner", "r3d1s-s3nha-al3atoria-0123", "smtp.provedor.com",
                "agendai", "r4bb1t-s3nha-al3atoria-01", "https://agendai.app");
    }

    @Test
    @DisplayName("Com problema, a aplicação não sobe e a mensagem lista todos")
    void failsFast() {
        assertThatThrownBy(() -> new ProductionConfigValidator(props("x", List.of(), false), "", "postgres", "postgres", "", " ", " ", "curta-15-chars!", "http://inseguro"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET").hasMessageContaining("CORS_ALLOWED_ORIGINS").hasMessageContaining("DATABASE_URL")
                .hasMessageContaining("DATABASE_USERNAME").hasMessageContaining("REDIS_PASSWORD").hasMessageContaining("secure-cookie")
                .hasMessageContaining("MAIL_HOST").hasMessageContaining("PUBLIC_URL").hasMessageContaining("RABBITMQ_PASSWORD")
                .hasMessageContaining("RABBITMQ_USERNAME");
        assertThatThrownBy(() -> new ProductionConfigValidator(props(GOOD_SECRET, List.of("https://agendai.app"), true),
                "jdbc:postgresql://db:5432/agendai", "agendai_app", "agendai_owner", "r3d1s-s3nha-al3atoria-0123", null, null, null, null))
                .hasMessageContaining("MAIL_HOST").hasMessageContaining("PUBLIC_URL").hasMessageContaining("RABBITMQ_PASSWORD")
                .hasMessageNotContaining("JWT_SECRET");
    }

    @Test
    @DisplayName("Fronteiras do segredo: 32 caracteres e 10 caracteres diferentes passam; 31 ou 9 diferentes, não")
    void secretBoundaries() {
        List<String> cors = List.of("https://agendai.app");
        String ten = "Zx9!k2Lq#v";                                                 // 10 caracteres diferentes
        assertThat(ProductionConfigValidator.problems((ten + ten + ten + "Zx"), cors, "jdbc:postgresql://db/agendai")).isEmpty();
        assertThat(ProductionConfigValidator.problems((ten + ten + ten + "Z"), cors, "jdbc:postgresql://db/agendai")).anyMatch(p -> p.startsWith("JWT_SECRET: mínimo"));
        String nine = "Zx9!k2Lq#";
        assertThat(ProductionConfigValidator.problems((nine + nine + nine + nine), cors, "jdbc:postgresql://db/agendai")).anyMatch(p -> p.startsWith("JWT_SECRET: parece"));
    }

    private static AgendaiProperties props(String secret, List<String> origins, boolean secureCookie) {
        return new AgendaiProperties(new AgendaiProperties.Jwt(secret, 15), new AgendaiProperties.Refresh(30, secureCookie),
                new AgendaiProperties.Login(5, 15), new AgendaiProperties.RateLimit(20, 120, 1200), 65536, new AgendaiProperties.Cors(origins));
    }

    @Test
    @DisplayName("PUBLIC_URL: https com host, sem caminho, consulta nem fragmento")
    void publicUrl() {
        assertThat(ProductionConfigValidator.validPublicUrl("https://agendai.app")).isTrue();
        assertThat(ProductionConfigValidator.validPublicUrl("https://agendai.app:8443")).isTrue();
        for (String bad : new String[]{"https://", "https:agendai.app", "http://agendai.app", "https://agendai.app/x", "https://agendai.app?a=1",
                "https://agendai.app#x", "agendai.app", "https://a b", null}) {
            assertThat(ProductionConfigValidator.validPublicUrl(bad)).as(String.valueOf(bad)).isFalse();
        }
    }

    @Test
    @DisplayName("Sessão em produção: Redis com senha de 16+ caracteres e cookie do refresh só por https")
    void sessionRules() {
        assertThat(ProductionConfigValidator.sessionProblems("r3d1s-s3nha-al3a", true)).isEmpty();
        assertThat(ProductionConfigValidator.sessionProblems("curta-15-chars!", true)).singleElement().asString().startsWith("REDIS_PASSWORD");
        assertThat(ProductionConfigValidator.sessionProblems(null, true)).singleElement().asString().startsWith("REDIS_PASSWORD");
        assertThat(ProductionConfigValidator.sessionProblems("r3d1s-s3nha-al3a", false)).singleElement().asString().contains("secure-cookie");
    }
}
