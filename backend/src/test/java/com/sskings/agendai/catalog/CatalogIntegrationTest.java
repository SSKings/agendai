package com.sskings.agendai.catalog;

import com.sskings.agendai.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

class CatalogIntegrationTest extends IntegrationTest {

    static Map<String, Object> service(String name, int duration, int priceCents) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("durationMinutes", duration);
        m.put("bufferMinutes", 5);
        m.put("priceCents", priceCents);
        m.put("active", true);
        return m;
    }

    static Map<String, Object> professional(String name, List<String> serviceIds) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("active", true);
        m.put("serviceIds", serviceIds);
        return m;
    }

    private String services(Session s) {
        return "/api/tenants/" + s.tenantId() + "/services";
    }

    private String professionals(Session s) {
        return "/api/tenants/" + s.tenantId() + "/professionals";
    }

    private void assertError(MockHttpServletResponse r, int status, String message) throws Exception {
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(status);
        assertThat(body(r).get("message").asString()).isEqualTo(message);
    }

    @Test
    @DisplayName("Serviços: o dono cadastra, lista em ordem alfabética, altera e desativa; nome repetido (sem diferenciar maiúsculas) é recusado")
    void servicesCrud() throws Exception {
        Session ana = register();
        MockHttpServletResponse created = call(post(services(ana)), ana.token(), service("Corte", 30, 4500));
        assertThat(created.getStatus()).isEqualTo(201);
        JsonNode corte = body(created);
        assertThat(corte.get("name").asString()).isEqualTo("Corte");
        assertThat(corte.get("durationMinutes").asInt()).isEqualTo(30);
        assertThat(corte.get("bufferMinutes").asInt()).isEqualTo(5);
        assertThat(corte.get("priceCents").asInt()).isEqualTo(4500);
        assertThat(corte.get("active").asBoolean()).isTrue();
        assertThat(call(post(services(ana)), ana.token(), service("Barba", 20, 3000)).getStatus()).isEqualTo(201);
        assertError(call(post(services(ana)), ana.token(), service("  CORTE ", 40, 1)), 409, "Service name already in use");

        JsonNode list = body(call(get(services(ana)), ana.token(), null));
        assertThat(list.findValuesAsString("name")).containsExactly("Barba", "Corte");

        Map<String, Object> change = service("Corte masculino", 40, 5000);
        change.put("active", false);
        MockHttpServletResponse updated = call(put(services(ana) + "/" + corte.get("id").asString()), ana.token(), change);
        assertThat(updated.getStatus()).isEqualTo(200);
        assertThat(body(updated).get("name").asString()).isEqualTo("Corte masculino");
        assertThat(body(updated).get("active").asBoolean()).isFalse();
        assertThat(body(updated).get("priceCents").asInt()).isEqualTo(5000);
        JsonNode persisted = body(call(get(services(ana)), ana.token(), null)).get(1);
        assertThat(persisted.get("name").asString()).isEqualTo("Corte masculino");
        assertThat(persisted.get("durationMinutes").asInt()).isEqualTo(40);
        assertThat(persisted.get("bufferMinutes").asInt()).isEqualTo(5);
        assertThat(persisted.get("priceCents").asInt()).isEqualTo(5000);
        assertThat(persisted.get("active").asBoolean()).isFalse();
        // o próprio nome não conta como repetido
        assertThat(call(put(services(ana) + "/" + corte.get("id").asString()), ana.token(), change).getStatus()).isEqualTo(200);
        assertError(call(put(services(ana) + "/" + UUID.randomUUID()), ana.token(), change), 404, "Service not found");
        String barba = list.get(0).get("id").asString();
        assertError(call(put(services(ana) + "/" + barba), ana.token(), service("CORTE MASCULINO", 20, 3000)), 409, "Service name already in use");
    }

    @Test
    @DisplayName("Serviço: duração de 5 a 480 min, intervalo de 0 a 120, preço não negativo, nome sem caracteres de controle")
    void serviceValidation() throws Exception {
        Session ana = register();
        for (Map<String, Object> bad : List.of(service("A", 4, 0), service("A", 481, 0), service("A", 30, -1), service(" ", 30, 0),
                service("Corte\u0000falso", 30, 0), service("x".repeat(101), 30, 0))) {
            assertError(call(post(services(ana)), ana.token(), bad), 400, "Validation failed");
        }
        Map<String, Object> buffer = service("A", 30, 0);
        buffer.put("bufferMinutes", 121);
        assertError(call(post(services(ana)), ana.token(), buffer), 400, "Validation failed");
        Map<String, Object> extra = service("A", 30, 0);
        extra.put("tenantId", UUID.randomUUID().toString());
        assertError(call(post(services(ana)), ana.token(), extra), 400, "Malformed JSON request");
        assertThat(call(post(services(ana)), ana.token(), service("Limite", 480, 0)).getStatus()).isEqualTo(201);
        Map<String, Object> top = service("x".repeat(100), 30, 10_000_000);
        top.put("bufferMinutes", 120);
        assertThat(call(post(services(ana)), ana.token(), top).getStatus()).as("limites exatos aceitos").isEqualTo(201);
        Map<String, Object> zeroBuffer = service("Sem intervalo", 30, 0);
        zeroBuffer.put("bufferMinutes", 0);
        assertThat(call(post(services(ana)), ana.token(), zeroBuffer).getStatus()).isEqualTo(201);
        assertError(call(post(services(ana)), ana.token(), service("Passo", 32, 0)), 400, CatalogService.FIVE_MINUTE_STEPS);
        Map<String, Object> oddBuffer = service("Passo", 30, 0);
        oddBuffer.put("bufferMinutes", 7);
        assertError(call(post(services(ana)), ana.token(), oddBuffer), 400, CatalogService.FIVE_MINUTE_STEPS);
        String someId = body(call(get(services(ana)), ana.token(), null)).get(0).get("id").asString();
        assertError(call(put(services(ana) + "/" + someId), ana.token(), service("Passo", 33, 0)), 400, CatalogService.FIVE_MINUTE_STEPS);
        Map<String, Object> negBuffer = service("B", 30, 0);
        negBuffer.put("bufferMinutes", -1);
        assertError(call(post(services(ana)), ana.token(), negBuffer), 400, "Validation failed");
        assertError(call(post(services(ana)), ana.token(), service("C", 30, 10_000_001)), 400, "Validation failed");
        for (String field : new String[]{"durationMinutes", "bufferMinutes", "priceCents", "active", "name"}) {
            Map<String, Object> missing = service("D", 30, 0);
            missing.remove(field);
            assertError(call(post(services(ana)), ana.token(), missing), 400, "Validation failed");
        }
        // espaços e quebras de linha viram um espaço só (nada de linha falsa em log ou CSV)
        assertThat(body(call(post(services(ana)), ana.token(), service("  Corte\r\n  feminino ", 30, 0))).get("name").asString())
                .isEqualTo("Corte feminino");
        assertThat(call(post(services(ana)), ana.token(), service("Mínimo", 5, 0)).getStatus()).isEqualTo(201);
    }

    @Test
    @DisplayName("Profissionais: o dono cadastra com os serviços que cada um faz, altera a lista e desativa")
    void professionalsCrud() throws Exception {
        Session ana = register();
        String corte = body(call(post(services(ana)), ana.token(), service("Corte", 30, 4500))).get("id").asString();
        String barba = body(call(post(services(ana)), ana.token(), service("Barba", 20, 3000))).get("id").asString();
        MockHttpServletResponse created = call(post(professionals(ana)), ana.token(), professional("João", List.of(corte, barba, corte)));
        assertThat(created.getStatus()).isEqualTo(201);
        JsonNode joao = body(created);
        assertThat(joao.get("serviceIds").valueStream().map(JsonNode::asString).toList()).containsExactlyInAnyOrder(corte, barba);
        assertError(call(post(professionals(ana)), ana.token(), professional("joão", List.of())), 409, "Professional name already in use");
        assertThat(call(post(professionals(ana)), ana.token(), professional("Beto", List.of())).getStatus()).isEqualTo(201);

        Map<String, Object> change = professional("João Silva", List.of(barba));
        change.put("active", false);
        MockHttpServletResponse updated = call(put(professionals(ana) + "/" + joao.get("id").asString()), ana.token(), change);
        assertThat(updated.getStatus()).isEqualTo(200);
        assertThat(body(updated).get("serviceIds").valueStream().map(JsonNode::asString).toList()).containsExactly(barba);
        assertThat(body(updated).get("active").asBoolean()).isFalse();

        JsonNode list = body(call(get(professionals(ana)), ana.token(), null));
        assertThat(list.findValuesAsString("name")).containsExactly("Beto", "João Silva");
        assertThat(list.get(0).get("serviceIds").isEmpty()).isTrue();
        assertThat(list.get(1).get("serviceIds").valueStream().map(JsonNode::asString).toList())
                .as("vínculos antigos removidos na edição").containsExactly(barba);
        String beto = list.get(0).get("id").asString();
        assertError(call(put(professionals(ana) + "/" + beto), ana.token(), professional("joão silva", List.of())), 409,
                "Professional name already in use");
        // reeditar com um serviço que já estava ligado (e outro que volta) não duplica nem falha
        MockHttpServletResponse again = call(put(professionals(ana) + "/" + joao.get("id").asString()), ana.token(),
                professional("João Silva", List.of(barba, corte)));
        assertThat(again.getStatus()).isEqualTo(200);
        assertThat(body(call(get(professionals(ana)), ana.token(), null)).get(1).get("serviceIds").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder(barba, corte);
        assertError(call(put(professionals(ana) + "/" + UUID.randomUUID()), ana.token(), change), 404, "Professional not found");
        assertError(call(post(professionals(ana)), ana.token(), professional("Caio", List.of(UUID.randomUUID().toString()))),
                400, "Unknown service");
        List<String> tooMany = java.util.stream.Stream.generate(() -> UUID.randomUUID().toString()).limit(101).toList();
        assertError(call(post(professionals(ana)), ana.token(), professional("Caio", tooMany)), 400, "Validation failed");
        java.util.List<String> withNull = new java.util.ArrayList<>();
        withNull.add(null);
        assertError(call(post(professionals(ana)), ana.token(), professional("Caio", withNull)), 400, "Validation failed");
        Map<String, Object> noList = professional("Caio", List.of());
        noList.remove("serviceIds");
        assertError(call(post(professionals(ana)), ana.token(), noList), 400, "Validation failed");
    }

    @Test
    @DisplayName("Isolamento: estabelecimento alheio é 404, e serviço de outro estabelecimento não pode ser ligado a um profissional")
    void isolation() throws Exception {
        Session ana = register();
        Session eve = register("Eve", email(), "Loja da Eve");
        String corte = body(call(post(services(ana)), ana.token(), service("Corte", 30, 4500))).get("id").asString();
        String joao = body(call(post(professionals(ana)), ana.token(), professional("João", List.of(corte)))).get("id").asString();

        assertError(call(get(services(ana)), eve.token(), null), 404, "Tenant not found");
        assertError(call(post(services(ana)), eve.token(), service("X", 30, 0)), 404, "Tenant not found");
        assertError(call(put(services(ana) + "/" + corte), eve.token(), service("X", 30, 0)), 404, "Tenant not found");
        assertError(call(get(professionals(ana)), eve.token(), null), 404, "Tenant not found");
        assertError(call(post(professionals(ana)), eve.token(), professional("X", List.of())), 404, "Tenant not found");
        assertError(call(put(professionals(ana) + "/" + joao), eve.token(), professional("X", List.of())), 404, "Tenant not found");
        // pelo estabelecimento dela, os ids da Ana não existem
        assertError(call(put(services(eve) + "/" + corte), eve.token(), service("X", 30, 0)), 404, "Service not found");
        assertError(call(put(professionals(eve) + "/" + joao), eve.token(), professional("X", List.of())), 404, "Professional not found");
        assertError(call(post(professionals(eve)), eve.token(), professional("Intruso", List.of(corte))), 400, "Unknown service");
        String eveService = body(call(post(services(eve)), eve.token(), service("Unha", 30, 0))).get("id").asString();
        assertError(call(put(professionals(ana) + "/" + joao), ana.token(), professional("João", List.of(eveService))), 400, "Unknown service");
        assertThat(body(call(get(services(ana)), ana.token(), null)).findValuesAsString("name")).containsExactly("Corte");
        assertThat(call(get(services(ana)), null, null).getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("Profissional da equipe vê o catálogo, mas só o dono altera (403)")
    void onlyOwnerWrites() throws Exception {
        Session ana = register();
        Session bia = register("Bia", email(), "Studio Bia");
        addMember(ana.tenantId(), bia.userId(), "PROFESSIONAL");
        String corte = body(call(post(services(ana)), ana.token(), service("Corte", 30, 4500))).get("id").asString();
        assertThat(body(call(get(services(ana)), bia.token(), null)).findValuesAsString("name")).containsExactly("Corte");
        String joao = body(call(post(professionals(ana)), ana.token(), professional("João", List.of(corte)))).get("id").asString();
        JsonNode team = body(call(get(professionals(ana)), bia.token(), null));
        assertThat(team.findValuesAsString("name")).containsExactly("João");
        assertThat(team.get(0).get("id").asString()).isEqualTo(joao);
        assertError(call(post(services(ana)), bia.token(), service("Barba", 20, 0)), 403, "Only owners can do this");
        assertError(call(put(services(ana) + "/" + corte), bia.token(), service("Barba", 20, 0)), 403, "Only owners can do this");
        assertError(call(post(professionals(ana)), bia.token(), professional("Bia", List.of())), 403, "Only owners can do this");
        assertError(call(put(professionals(ana) + "/" + UUID.randomUUID()), bia.token(), professional("Bia", List.of())), 403,
                "Only owners can do this");
    }

    @Test
    @DisplayName("Limites por estabelecimento: 100 serviços e 50 profissionais (listas sempre limitadas)")
    void limits() throws Exception {
        Session ana = register();
        for (int i = 0; i < CatalogService.MAX_SERVICES; i++) {
            assertThat(call(post(services(ana)), ana.token(), service("S" + i, 30, 0)).getStatus()).isEqualTo(201);
        }
        assertError(call(post(services(ana)), ana.token(), service("Mais um", 30, 0)), 409, "Service limit reached");
        for (int i = 0; i < CatalogService.MAX_PROFESSIONALS; i++) {
            assertThat(call(post(professionals(ana)), ana.token(), professional("P" + i, List.of())).getStatus()).isEqualTo(201);
        }
        assertError(call(post(professionals(ana)), ana.token(), professional("Mais um", List.of())), 409, "Professional limit reached");
    }

    @Test
    @DisplayName("Teto sob concorrência: com 95 serviços, 10 criações simultâneas → exatamente 5 entram, 5 recebem 409")
    void limitUnderConcurrency() throws Exception {
        Session ana = register();
        for (int i = 0; i < CatalogService.MAX_SERVICES - 5; i++) {
            assertThat(call(post(services(ana)), ana.token(), service("S" + i, 30, 0)).getStatus()).isEqualTo(201);
        }
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(10);
        try {
            List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 10; i++) {
                String name = "Paralelo " + i;
                results.add(pool.submit(() -> {
                    start.await();
                    return call(post(services(ana)), ana.token(), service(name, 30, 0)).getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new java.util.ArrayList<>();
            for (var r : results) statuses.add(r.get(30, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(statuses).filteredOn(st -> st == 201).hasSize(5);
            assertThat(statuses).filteredOn(st -> st == 409).hasSize(5);
        } finally {
            pool.shutdownNow();
        }
        assertThat(body(call(get(services(ana)), ana.token(), null)).size()).isEqualTo(CatalogService.MAX_SERVICES);
    }

    @Test
    @DisplayName("Lista em ordem alfabética sem diferenciar maiúsculas (mesma expressão do índice)")
    void caseInsensitiveOrder() throws Exception {
        Session ana = register();
        for (String n : new String[]{"barba", "Corte", "ana", "Bigode"}) {
            assertThat(call(post(services(ana)), ana.token(), service(n, 30, 0)).getStatus()).isEqualTo(201);
            assertThat(call(post(professionals(ana)), ana.token(), professional(n, List.of())).getStatus()).isEqualTo(201);
        }
        assertThat(body(call(get(services(ana)), ana.token(), null)).findValuesAsString("name")).containsExactly("ana", "barba", "Bigode", "Corte");
        assertThat(body(call(get(professionals(ana)), ana.token(), null)).findValuesAsString("name"))
                .containsExactly("ana", "barba", "Bigode", "Corte");
    }
}
