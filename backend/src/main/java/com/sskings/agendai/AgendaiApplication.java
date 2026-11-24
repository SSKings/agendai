package com.sskings.agendai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * Sem UserDetailsServiceAutoConfiguration: o Spring criaria um usuário "user" com senha gerada (e escrita no log).
 * O login do Agendaí é só o do AuthService.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class AgendaiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgendaiApplication.class, args);
    }
}
