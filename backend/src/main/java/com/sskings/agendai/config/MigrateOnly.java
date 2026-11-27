package com.sskings.agendai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.function.IntConsumer;

/**
 * Papel "migrate" (produção: serviço que roda uma vez antes da API e do worker). O Flyway migra ao subir o contexto;
 * quando chega aqui, terminou: o processo sai com o código do Spring (0 = migrado). Só ele recebe a senha do dono das
 * tabelas — a API, exposta à internet, não.
 */
@Slf4j
@Component
@Profile("migrate")
class MigrateOnly implements ApplicationRunner {

    private final ConfigurableApplicationContext context;
    private final IntConsumer exit;

    @Autowired
    MigrateOnly(ConfigurableApplicationContext context) {
        this(context, System::exit);
    }

    MigrateOnly(ConfigurableApplicationContext context, IntConsumer exit) {
        this.context = context;
        this.exit = exit;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Migrações aplicadas; saindo");
        exit.accept(SpringApplication.exit(context));
    }
}
