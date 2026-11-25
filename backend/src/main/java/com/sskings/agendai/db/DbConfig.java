package com.sskings.agendai.db;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
class DbConfig {

    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new RlsTransactionManager(emf);
    }
}
