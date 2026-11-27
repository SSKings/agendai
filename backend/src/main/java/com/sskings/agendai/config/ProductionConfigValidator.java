package com.sskings.agendai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Perfil prod: a aplicação NÃO sobe com configuração insegura — lista todos os problemas de uma vez.
 * <ul>
 *   <li>JWT_SECRET com 32+ caracteres, variado, e não de exemplo/teste (quem tem o segredo forja qualquer login).</li>
 *   <li>CORS_ALLOWED_ORIGINS obrigatório, só https e sem curinga.</li>
 *   <li>DATABASE_URL de PostgreSQL.</li>
 *   <li>A API conecta com um usuário só de DML, diferente do dono das tabelas (que roda as migrações) e do
 *       superusuário: uma falha de SQL não vira controle do banco (lição da revisão 5 do ShopEasy).</li>
 *   <li>Cada processo tem um papel — {@code prod,api}, {@code prod,worker} ou {@code prod,migrate} — e só recebe (e
 *       só é cobrado por) o que usa: RabbitMQ só no worker; JWT, CORS, Redis e endereço do front só na API; o dono
 *       das tabelas só no migrate.</li>
 * </ul>
 */
@Component
@Profile("prod")
public class ProductionConfigValidator {

    public ProductionConfigValidator(Environment env, AgendaiProperties props, @Value("${spring.datasource.url:}") String databaseUrl,
                                     @Value("${spring.datasource.username:}") String appUser,
                                     @Value("${spring.flyway.user:}") String migrationUser,
                                     @Value("${spring.data.redis.password:}") String redisPassword,
                                     @Value("${spring.mail.host:}") String mailHost,
                                     @Value("${spring.rabbitmq.username:}") String rabbitUser,
                                     @Value("${spring.rabbitmq.password:}") String rabbitPassword,
                                     @Value("${agendai.public-url:}") String publicUrl) {
        boolean api = env.matchesProfiles("api");
        boolean worker = env.matchesProfiles("worker");
        boolean migrate = env.matchesProfiles("migrate");
        // sem papel, ou com mais de um: recusa e valida tudo (a lista sai completa de uma vez)
        boolean unknown = (api ? 1 : 0) + (worker ? 1 : 0) + (migrate ? 1 : 0) != 1;
        Set<String> problems = new LinkedHashSet<>();
        if (unknown) {
            problems.add("SPRING_PROFILES_ACTIVE: em produção cada processo tem um papel — prod,api, prod,worker ou prod,migrate");
        }
        if (databaseUrl == null || !databaseUrl.startsWith("jdbc:postgresql:")) problems.add(DATABASE_URL);
        if (appUser == null || appUser.isBlank() || appUser.equals("postgres")) problems.add(DATABASE_USERNAME);
        if (api || unknown) {
            problems.addAll(problems(props.jwt().secret(), props.cors().allowedOrigins(), databaseUrl));
            problems.addAll(sessionProblems(redisPassword, props.refresh().secureCookie()));
            if (!validPublicUrl(publicUrl)) {
                problems.add("PUBLIC_URL: informe o endereço https do front, só esquema e host (ex.: https://agendai.app) — vai nos links dos e-mails");
            }
        }
        if (worker || unknown) {
            if (rabbitUser == null || rabbitUser.isBlank()) {
                problems.add("RABBITMQ_USERNAME: informe o usuário do RabbitMQ");
            }
            if (rabbitPassword == null || rabbitPassword.length() < 16) {
                problems.add("RABBITMQ_PASSWORD: informe uma senha aleatória de pelo menos 16 caracteres (openssl rand -hex 24)");
            }
        }
        if ((api || worker || unknown) && (mailHost == null || mailHost.isBlank())) {
            problems.add("MAIL_HOST: informe o SMTP do provedor (código de confirmação e avisos do agendamento vão por e-mail)");
        }
        if (migrate || unknown) {
            problems.addAll(databaseUserProblems(appUser, migrationUser));
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Configuração de produção insegura:\n - " + String.join("\n - ", problems));
        }
    }

    private static final String DATABASE_USERNAME =
            "DATABASE_USERNAME: a aplicação usa um usuário só de dados, diferente do dono das tabelas e do superusuário";
    private static final String DATABASE_URL = "DATABASE_URL: informe o PostgreSQL de produção (jdbc:postgresql://...)";

    static List<String> problems(String jwtSecret, List<String> origins, String databaseUrl) {
        List<String> p = new ArrayList<>();
        String secret = jwtSecret == null ? "" : jwtSecret;
        String lower = secret.toLowerCase(Locale.ROOT);
        if (secret.length() < 32) {
            p.add("JWT_SECRET: mínimo de 32 caracteres (gere com: openssl rand -base64 48)");
        } else if (secret.chars().distinct().count() < 10 || lower.contains("change") || lower.contains("test")
                || lower.contains("secret") || lower.contains("example")) {
            p.add("JWT_SECRET: parece de exemplo ou de teste; gere um aleatório (openssl rand -base64 48)");
        }
        if (origins == null || origins.isEmpty()) {
            p.add("CORS_ALLOWED_ORIGINS: informe a origem do front-end (ex.: https://bolso.seudominio.com.br)");
        } else {
            for (String o : origins) {
                if (o.contains("*") || !o.startsWith("https://")) {
                    p.add("CORS_ALLOWED_ORIGINS: '" + o + "' — só origens https exatas, sem curinga");
                }
            }
        }
        if (databaseUrl == null || !databaseUrl.startsWith("jdbc:postgresql:")) {
            p.add(DATABASE_URL);
        }
        return p;
    }

    /** Redis com senha (guarda limites e, depois, reservas) e cookie do refresh só por https. */
    static List<String> sessionProblems(String redisPassword, boolean secureCookie) {
        List<String> p = new ArrayList<>();
        if (redisPassword == null || redisPassword.length() < 16) {
            p.add("REDIS_PASSWORD: informe uma senha aleatória de pelo menos 16 caracteres (openssl rand -hex 24)");
        }
        if (!secureCookie) {
            p.add("agendai.refresh.secure-cookie: em produção o cookie do refresh precisa ser Secure (só https)");
        }
        return p;
    }

    static List<String> databaseUserProblems(String appUser, String migrationUser) {
        List<String> p = new ArrayList<>();
        if (migrationUser == null || migrationUser.isBlank()) {
            p.add("DATABASE_MIGRATION_USERNAME: informe o dono das tabelas (o Flyway migra com ele)");
        }
        if (appUser == null || appUser.isBlank() || appUser.equals(migrationUser) || appUser.equals("postgres")) {
            p.add(DATABASE_USERNAME);
        }
        return p;
    }

    /** https, com host, sem caminho, consulta nem fragmento (o link é montado em cima). */
    static boolean validPublicUrl(String url) {
        try {
            java.net.URI u = new java.net.URI(url);
            return "https".equals(u.getScheme()) && u.getHost() != null && u.getPath().isEmpty()   // com host, há caminho (mesmo vazio)
                    && u.getQuery() == null && u.getFragment() == null;
        } catch (java.net.URISyntaxException | NullPointerException e) {
            return false;
        }
    }
}
