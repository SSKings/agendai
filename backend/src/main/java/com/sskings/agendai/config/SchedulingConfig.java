package com.sskings.agendai.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Jobs periódicos (ex.: expirar pendentes). Cada um tem de ser seguro com várias instâncias rodando ao mesmo tempo. */
@Configuration
@EnableScheduling
class SchedulingConfig {}
