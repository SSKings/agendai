package com.sskings.agendai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.support.GenericApplicationContext;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MigrateOnlyTest {

    @Test
    @DisplayName("Papel migrate: depois de subir (o Flyway já migrou), fecha o contexto e sai com o código do Spring")
    void exitsAfterStartup() {
        GenericApplicationContext ctx = new GenericApplicationContext();
        ctx.registerBean(ExitCodeGenerator.class, () -> () -> 3);
        ctx.refresh();
        List<Integer> codes = new ArrayList<>();
        new MigrateOnly(ctx, codes::add).run(null);
        assertThat(codes).containsExactly(3);
        assertThat(ctx.isActive()).isFalse();
        assertThat(new MigrateOnly(ctx)).as("o construtor do Spring sai de verdade (System.exit)").isNotNull();
    }
}
