package com.sskings.agendai.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** O contrato com o front depende destas regras (docs/arquitetura.md, "Contrato da API"). */
class OpenApiConfigTest {

    private final OpenApiCustomizer customizer = new OpenApiConfig().requiredUnlessNullable();

    private static Schema<?> ref(String name) {
        return new Schema<>().$ref("#/components/schemas/" + name);
    }

    @Test
    @DisplayName("Especificação sem componentes ou sem schemas: nada a ajustar (e nada quebra)")
    void emptySpecs() {
        assertThatCode(() -> customizer.customise(new OpenAPI())).doesNotThrowAnyException();
        assertThatCode(() -> customizer.customise(new OpenAPI().components(new Components()))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Respostas: todo campo obrigatório, menos os anuláveis (3.0 nullable, 3.1 type null); $ref anulável vira anyOf [ref, null]")
    void responsesRequiredUnlessNullable() {
        Schema<?> nullableString = new StringSchema();
        nullableString.setTypes(Set.of("string", "null"));
        Schema<?> nullableRef = ref("Owner");
        nullableRef.setType("null");
        Schema<?> view = new ObjectSchema()
                .addProperty("id", new StringSchema())
                .addProperty("note", nullableString)
                .addProperty("legacy", new StringSchema().nullable(true))
                .addProperty("owner", nullableRef)
                .addProperty("tags", new ArraySchema().items(ref("Tag")));
        view.setRequired(new ArrayList<>(List.of("id", "legacy")));
        Schema<?> onlyOptional = new ObjectSchema().addProperty("maybe", new StringSchema().nullable(true));
        OpenAPI api = new OpenAPI().components(new Components()
                .addSchemas("View", view).addSchemas("OnlyOptional", onlyOptional).addSchemas("Empty", new ObjectSchema()));

        customizer.customise(api);

        assertThat(view.getRequired()).containsExactly("id", "tags");
        Schema<?> owner = view.getProperties().get("owner");
        assertThat(owner.getAnyOf()).hasSize(2);
        assertThat(owner.getAnyOf().get(0).get$ref()).isEqualTo("#/components/schemas/Owner");
        assertThat(owner.getAnyOf().get(1).getTypes()).containsExactly("null");
        assertThat(onlyOptional.getRequired()).as("sem obrigatórios: some a lista").isNull();
        assertThat(OpenApiConfig.nullable(new StringSchema())).isFalse();
    }

    @Test
    @DisplayName("Requisições (e o que elas referenciam, inclusive em listas) seguem a validação: nada é forçado a obrigatório")
    void requestsKeepValidationRules() {
        Schema<?> nested = new ObjectSchema().addProperty("note", new StringSchema());
        Schema<?> item = new ObjectSchema().addProperty("qty", new StringSchema());
        Schema<?> request = new ObjectSchema()
                .addProperty("name", new StringSchema())
                .addProperty("nested", ref("Nested"))
                .addProperty("items", new ArraySchema().items(ref("Item")))
                .addProperty("again", ref("Nested"));                       // referência repetida: visitada uma vez
        Schema<?> response = new ObjectSchema().addProperty("id", new StringSchema());
        Operation create = new Operation().requestBody(new RequestBody().content(new Content()
                .addMediaType("application/json", new MediaType().schema(ref("Request")))
                .addMediaType("text/plain", new MediaType())));                // mídia sem schema
        Operation noBody = new Operation();
        Operation emptyBody = new Operation().requestBody(new RequestBody());  // corpo sem conteúdo declarado
        OpenAPI api = new OpenAPI()
                .components(new Components().addSchemas("Request", request).addSchemas("Nested", nested)
                        .addSchemas("Item", item).addSchemas("Response", response))
                .paths(new Paths()
                        .addPathItem("/a", new PathItem().post(create).get(noBody))
                        .addPathItem("/b", new PathItem().put(emptyBody)));

        customizer.customise(api);

        assertThat(request.getRequired()).isNull();
        assertThat(nested.getRequired()).isNull();
        assertThat(item.getRequired()).isNull();
        assertThat(response.getRequired()).containsExactly("id");
    }
}
