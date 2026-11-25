# Decisões de arquitetura (ADRs)

## ADR-001 — Monolito modular + worker de notificações

**Contexto:** projeto de uma pessoa, com foco em qualidade. Microsserviços trariam rede, versionamento e operação
sem benefício real neste tamanho.
**Decisão:** a API é um monolito com módulos de fronteiras verificadas por teste (ArchUnit). O envio de notificações
fica num **worker separado**, ligado por RabbitMQ — a parte que de fato se beneficia de isolamento (falha de e-mail não
derruba a API) e de escala independente.
**Consequência:** deploy simples; se um módulo precisar virar serviço, as fronteiras já existem.

## ADR-002 — Conflito de horário resolvido pelo banco (restrição de exclusão)

**Contexto:** "nunca dois clientes no mesmo horário" é a regra mais importante do produto e tem de valer com várias
instâncias e requisições simultâneas. Checar no código ("existe agendamento?" e depois inserir) tem janela de corrida.
**Decisão:** `EXCLUDE USING gist (professional_id WITH =, period WITH &&)` nos agendamentos ativos. O Redis só segura
o horário durante o preenchimento (experiência), nunca é a garantia.
**Consequência:** a regra vale mesmo com bug no código; exige a extensão `btree_gist` e testes de concorrência reais.

## ADR-003 — Eventos pelo padrão outbox

**Contexto:** publicar no RabbitMQ dentro da transação do agendamento ou depois dela perde eventos quando um dos dois
falha.
**Decisão:** o evento é gravado em `outbox_events` na mesma transação; um publicador o envia com confirmação; o worker
é idempotente (`processed_events`) e usa novas tentativas com espera crescente e fila de falhas.
**Consequência:** entrega "pelo menos uma vez" com efeito "exatamente uma vez" no worker; atraso de alguns segundos.

## ADR-004 — TDD e 100% de cobertura exigidos pelo CI

**Contexto:** o objetivo do projeto é mostrar engenharia confiável; os projetos anteriores mostraram que cobertura
abaixo de 100% esconde exatamente os ramos de segurança (limpeza de memória, regras do validador).
**Decisão:** testes antes do código; o CI exige 100% de linhas e ramos, com exceções listadas e justificadas; PIT
≥ 95% no domínio e na segurança.
**Consequência:** desenvolvimento mais lento no começo; cada funcionalidade nasce com a prova de que funciona.

## ADR-005 — Sessão: access token em memória e refresh token em cookie HttpOnly com rotação

**Contexto:** no Bolso, o JWT no `localStorage` ficou como risco aceito (um XSS leria o token).
**Decisão:** access token de 15 min só na memória do front; refresh token em cookie `HttpOnly; Secure;
SameSite=Strict`, de uso único com rotação; reuso de um refresh já trocado revoga a família inteira (sinal de roubo).
**Consequência:** um XSS não consegue levar uma sessão de longa duração; o front renova o token em segundo plano.

## ADR-006 — Isolamento entre estabelecimentos em três camadas, com Row-Level Security

**Contexto:** vazamento entre estabelecimentos é o pior incidente possível para o produto.
**Decisão:** (1) guard de acesso na API; (2) consultas e FKs compostas com `tenant_id`; (3) **RLS no PostgreSQL**, com o
`tenant_id` da requisição definido na transação (`set_config('app.tenant_id', ..., true)`, equivalente a `SET LOCAL`;
o usuário vai em `app.user_id`, definido pelo `RlsTransactionManager` no início de cada transação), e o usuário da API
sem permissão de ignorar o RLS. `users` e `refresh_tokens` são globais (sem `tenant_id`): ficam fora do RLS.
**Consequência:** um bug numa consulta não vaza dados; testes precisam cobrir as três camadas, inclusive cada uma
sozinha.

## ADR-007 — Redis para dados temporários e compartilhados

**Contexto:** limite por IP e reservas temporárias em memória local quebram com mais de uma instância.
**Decisão:** reservas (com expiração), limites por IP/estabelecimento e o cache de horários livres ficam no Redis.
Se o Redis cair, a API continua agendando (a garantia é o banco), sem cache e com limite degradado registrado em alerta.
**Consequência:** mais um serviço na pilha; comportamento com o Redis fora é testado.
