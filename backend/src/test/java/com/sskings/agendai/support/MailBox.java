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

    public String lastCodeTo(String to) throws Exception {
        String text = lastTextTo(to);
        if (text == null) return null;
        Matcher m = CODE.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + path)).build(), HttpResponse.BodyHandlers.ofString());
        return json.readTree(r.body());
    }
}
