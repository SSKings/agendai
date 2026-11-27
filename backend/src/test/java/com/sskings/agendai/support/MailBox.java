package com.sskings.agendai.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Caixa postal do Mailpit de teste (API HTTP): o último e-mail para um endereço e o código de 6 dígitos dele. */
public class MailBox {

    private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");
    private final String base;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    MailBox(String base) {
        this.base = base;
    }

    /** Texto do e-mail mais recente para {@code to}, ou null se não chegou nenhum. */
    public String lastTextTo(String to) throws Exception {
        JsonNode found = get("/api/v1/search?query=" + URLEncoder.encode("to:\"" + to + "\"", StandardCharsets.UTF_8));
        JsonNode messages = found.get("messages");
        if (messages == null || messages.isEmpty()) return null;
        JsonNode m = get("/api/v1/message/" + messages.get(0).get("ID").asString());
        return m.get("Subject").asString() + "\n" + m.get("Text").asString();
    }

    /** Remetente (endereço) e assunto do e-mail mais recente para {@code to}. */
    public String[] lastFromAndSubjectTo(String to) throws Exception {
        JsonNode found = get("/api/v1/search?query=" + URLEncoder.encode("to:\"" + to + "\"", StandardCharsets.UTF_8));
        JsonNode m = get("/api/v1/message/" + found.get("messages").get(0).get("ID").asString());
        return new String[]{m.get("From").get("Address").asString(), m.get("Subject").asString()};
    }

    /** Quantos e-mails para {@code to} têm {@code subject} no assunto. */
    public int countTo(String to, String subject) throws Exception {
        JsonNode found = get("/api/v1/search?query=" + URLEncoder.encode("to:\"" + to + "\" subject:\"" + subject + "\"", StandardCharsets.UTF_8));
        return found.get("messages") == null ? 0 : found.get("messages").size();
    }

    /**
     * Espera (até 20 s) chegarem {@code n} e-mails para {@code to} com {@code subject} no assunto: os avisos saem pelo
     * outbox e pelo worker, depois da resposta. Devolve quantos chegaram (pode ser mais que {@code n}: quem chama confere).
     */
    public int awaitCountTo(String to, String subject, int n) throws Exception {
        long until = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        int count = countTo(to, subject);
        while (count < n && System.nanoTime() < until) {
            Thread.sleep(100);
            count = countTo(to, subject);
        }
        return count;
    }

    /** Texto do e-mail mais recente para {@code to} com {@code subject} no assunto, esperando chegar (até 20 s). */
    public String awaitTextTo(String to, String subject) throws Exception {
        if (awaitCountTo(to, subject, 1) == 0) return null;
        JsonNode found = get("/api/v1/search?query=" + URLEncoder.encode("to:\"" + to + "\" subject:\"" + subject + "\"", StandardCharsets.UTF_8));
        JsonNode m = get("/api/v1/message/" + found.get("messages").get(0).get("ID").asString());
        return m.get("Subject").asString() + "\n" + m.get("Text").asString();
    }

    /** O código do e-mail de código mais recente (síncrono: já chegou quando a resposta voltou). */
    public String lastCodeTo(String to) throws Exception {
        JsonNode found = get("/api/v1/search?query=" + URLEncoder.encode("to:\"" + to + "\" subject:\"Seu código\"", StandardCharsets.UTF_8));
        JsonNode messages = found.get("messages");
        if (messages == null || messages.isEmpty()) return null;
        JsonNode msg = get("/api/v1/message/" + messages.get(0).get("ID").asString());
        String text = msg.get("Subject").asString() + "\n" + msg.get("Text").asString();
        Matcher m = CODE.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + path)).build(), HttpResponse.BodyHandlers.ofString());
        return json.readTree(r.body());
    }
}
