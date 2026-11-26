# Roadmap

Cada fase termina com o app utilizável, **100% coberto por testes**, revisado em segurança e com as metas de
desempenho da fase medidas. Nenhuma fase começa antes de a anterior estar mergeada.

| Fase | Conteúdo | Situação |
|---|---|---|
| 0 — Fundação | Base do projeto e rede de proteção no CI | concluída (PR #1) |
| 1 — Agenda | Serviços, profissionais, expediente, horários livres, agendamento sem conflito | em andamento |
| 2 — Agendamento público | Link público, reserva temporária, confirmação por código, cancelar/remarcar | |
| 3 — Eventos e notificações | Outbox, RabbitMQ, worker, lembretes | |
| 4 — Lista de espera e indicadores | Oferta de vaga, política de cancelamento, faltas, painel | |
| 5 — Nuvem | Terraform na AWS, deploy contínuo, demo pública | |

## Critério de pronto (vale para toda fase)

- [ ] Testes escritos antes; **100% de cobertura** de linhas e ramos (exceções listadas e justificadas)
- [ ] Mutação (PIT) ≥ 95% no domínio e na segurança
- [ ] Controles de segurança da fase com teste que falha sem eles; revisão do `revisor-seguranca` registrada
- [ ] Metas de desempenho da fase medidas com k6 e dentro do SLO; revisão do `revisor-desempenho`
- [ ] Contrato da API atualizado; tipos do front regerados
- [ ] Documentação atualizada (produto, arquitetura, segurança, ADRs)
- [ ] CI verde, inclusive a pilha de produção com upgrade e o scan de imagens

## Fase 0 — Fundação

**Entrega:** repositório pronto para o desenvolvimento com toda a rede de proteção, antes de qualquer funcionalidade.

- Backend Spring Boot 4 (Java 17), Flyway, Testcontainers com PostgreSQL e Redis. O RabbitMQ entra na fase 3, junto
  com o primeiro evento (outbox): antes disso seria infraestrutura sem uso para manter e escanear.
- Frontend React + TypeScript + Vite + Tailwind, com Vitest.
- Login da equipe (cadastro do dono, senha forte, bloqueio progressivo, access + refresh token com rotação).
- Estabelecimento (tenant) e isolamento em três camadas, inclusive RLS.
- CI: testes com cobertura 100%, PIT, contrato OpenAPI, migrações imutáveis, ArchUnit, pilha de produção com upgrade,
  Trivy, CodeQL; Dependabot; `main` protegida.
- Docker Compose de dev e de produção (nginx TLS, três usuários no banco, containers sem root).

**Testes-chave:** isolamento de todas as rotas (com o RLS sozinho também barrando); rotação e reuso de refresh token;
API não sobe com configuração insegura.

## Fase 1 — Agenda

Em quatro PRs: **1a** catálogo e equipe (serviços, profissionais, quem faz o quê) — PR #6; **1b** expediente, bloqueios
e horários livres — PR #7; **1c** agendamento sem conflito (restrição de exclusão no banco, agendamento manual pelos
horários livres, agenda do dia/semana, atendido/falta/cancelado) — PR #8; **1d** convite para a equipe (vínculo de um
profissional a uma conta), que também restringe o profissional à própria agenda.

- Serviços (duração, preço, preparo), profissionais, quem faz o quê, convite para a equipe.
- Expediente por profissional e dia, bloqueios e feriados.
- Cálculo de horários livres (fuso do estabelecimento) e agendamento manual pelo painel.
- Restrição de exclusão no banco (nunca dois agendamentos sobrepostos).
- Agenda do dia e da semana; marcar atendido, falta, cancelado.

**Testes-chave:** 100 pedidos simultâneos no mesmo horário → 1 agendamento; horários livres na virada do dia UTC ×
local; expediente alterado não apaga agendamentos. **Desempenho:** agenda da semana e horários livres dentro do SLO.

## Fase 2 — Agendamento público

Em quatro PRs: **2a** página pública por link, só leitura (serviço → profissional → dia → horários livres), com limite
por IP próprio; **2b** reserva temporária no Redis e confirmação por código no e-mail — PR #12; **2c** link pessoal para cancelar ou
remarcar e política do estabelecimento; **2d** cache dos horários livres e LGPD.

- Página pública por slug, fluxo serviço → profissional → dia → horário.
- Reserva temporária de 5 min no Redis; confirmação por código no e-mail.
- Link pessoal para cancelar ou remarcar dentro da política.
- Limite por IP e por estabelecimento no Redis; LGPD (finalidade, exportação, exclusão).

**Testes-chave:** reserva expira e libera o horário; robô não consegue segurar a agenda inteira; link de outro
agendamento não funciona; nenhum dado de outro cliente aparece. **Desempenho:** horários livres com cache < 150 ms p95.

## Fase 3 — Eventos e notificações

- Outbox na transação do agendamento; publicador com `SKIP LOCKED` e *publisher confirms*.
- Worker de notificações separado: e-mail, idempotência, novas tentativas, fila de falhas, alerta.
- Lembretes 24 h e 2 h antes, com job de trava distribuída; WhatsApp (API oficial) como canal opcional.

**Testes-chave:** RabbitMQ fora durante o agendamento → o aviso sai quando ele volta; evento entregue duas vezes →
um e-mail só; várias instâncias → cada lembrete uma vez. **Desempenho:** evento → envio < 30 s.

## Fase 4 — Lista de espera e indicadores

- Fila por dia/profissional/serviço; cancelamento oferece a vaga ao primeiro por tempo limitado, depois ao próximo.
- Política de cancelamento e registro de faltas.
- Painel: ocupação, cancelamentos, faltas, serviços mais procurados.

**Testes-chave:** dois aceites simultâneos da mesma vaga → só um vale; oferta expirada passa ao próximo.

## Fase 5 — Nuvem

- Terraform na AWS (opção econômica: VM pequena com Docker Compose; opção completa: ECS + RDS — decidir com o custo).
- Deploy contínuo da `main`, domínio com HTTPS, backups cifrados e teste de restauração.
- Demo pública com dados fictícios recriados todo dia; teste de carga com os números no README.

**Testes-chave:** `terraform plan` validado no CI; restauração de backup testada; smoke contra o ambiente publicado.
