# Segurança

Regra de ouro: **cada controle desta página tem um teste que falha sem ele.** Controle sem teste não conta como feito.
A coluna "Teste" aponta o teste que falha sem o controle (ou a fase em que o controle entra).

## O que protegemos

| Ativo | Por quê |
|---|---|
| Dados pessoais dos clientes (nome, e-mail, telefone, histórico) | LGPD; vazamento entre estabelecimentos seria grave |
| Agendas dos estabelecimentos | Concorrentes não podem ver a agenda nem a clientela uns dos outros |
| Contas da equipe | Quem entra como dono altera tudo |
| Rotas públicas de agendamento | Abertas na internet: alvo de spam, robôs e esgotamento da agenda |
| Credenciais de terceiros (e-mail, WhatsApp, AWS) | Dão acesso a envio em nome da loja e à infraestrutura |

## Ameaças e controles

| Ameaça | Controle | Teste |
|---|---|---|
| Ver ou alterar dados de outro estabelecimento | Guard de acesso a cada requisição + consultas filtradas por `tenant_id` + FKs compostas + **Row-Level Security** no PostgreSQL | Teste de isolamento varrendo **todas** as rotas com ids de outro estabelecimento; teste que desliga o guard e confirma que o RLS ainda barra. Hoje: `TenantIsolationTest`, `RowLevelSecurityTest` (sem guard, como `agendai_app`), `TenantAccessTest`; catálogo: `CatalogIntegrationTest.isolation` / `onlyOwnerWrites` (só o dono altera) e `CatalogRowLevelSecurityTest` (RLS sozinho e FK composta impedindo ligar dados de estabelecimentos diferentes); agenda: `ScheduleIntegrationTest` (`*Access`: 404 para profissional, serviço ou bloqueio alheio; só o dono altera) e `ScheduleRowLevelSecurityTest` (RLS, FK composta e CHECKs do banco) |
| Agendamento falso em nome de outra pessoa / spam | Pela página pública o agendamento nasce PENDING e só vale com o código de 6 dígitos enviado ao e-mail digitado (no banco, SHA-256 de "id:código"; 10 min; até 5 tentativas contadas por UPDATE atômico — a 5ª errada expira e libera o horário); comparação em tempo constante; o token de acesso (256 bits) só o cliente tem; telefone de cliente existente não revela nada nem muda o cadastro | `PublicReservationIntegrationTest` (`fullFlow`, `codeAttempts`, `existingCustomer`, `validation`) |
| Página pública vazando dados (equipe, clientes, outros estabelecimentos) | Rotas sem login só em `/api/public/{link}/…` (GET de leitura; POST de reserva, pedido de código e confirmação); o link vira o estabelecimento da transação por uma função que devolve só o id (`tenant_by_slug`), e daí o RLS de sempre; respostas só com nome/fuso, serviços ativos (sem preparo), profissionais ativos (nome) e horários livres; link malformado ou inexistente: o mesmo 404 | `PublicBookingIntegrationTest` (`browse`, `sameRulesAsPanel`, `isolation`, `readOnly`), `PublicRowLevelSecurityTest` |
| Robô varrendo a página pública; página pública derrubando o login da equipe | Limite por IP próprio (120/min) e por estabelecimento somando origens (1200/min), no Redis, separado do da autenticação; a página do cliente não chama a renovação de sessão (ADR-011) | `PublicBookingIntegrationTest.rateLimit`, `RateLimitFilterTest.publicPerIpAndPerBusiness`, front `publicPage.test.tsx` |
| Robô esgotando a agenda com reservas temporárias | Reserva no Redis por 5 min só de horário livre, `SET NX` por horário, no máximo 3 abertas por origem (IPv6 por /64), uso único; limite por IP e por estabelecimento; o pendente vence em 10 min e libera sozinho (o vencido não ocupa nem antes do job); Redis fora: a página não reserva (503), o painel segue | `PublicReservationIntegrationTest.holdRules` / `pendingExpires`, `RedisDownIntegrationTest.holdsNeedRedis` |
| Força bruta no login da equipe | Bloqueio progressivo por conta, limite por IP (IPv6 por /64), mesma resposta para e-mail inexistente | `AuthIntegrationTest.lockout` / `lockoutCap` / `login` (mesma resposta para e-mail inexistente), `RateLimitIntegrationTest.limitPerIp` / `ipv6Block`, `RateLimitFilterTest` (Redis fora: não derruba a API) |
| Senha fraca | Política com lista de senhas comuns e disfarces (`s3nh@`), sem nome/e-mail | `PasswordStrengthTest`, `AuthIntegrationTest.registerValidation` |
| Sessão roubada | Access token curto (15 min) **só em memória** no front + refresh token em cookie `HttpOnly; Secure; SameSite=Strict` com rotação e detecção de reuso; logout revoga tudo (ADR-005) | `AuthIntegrationTest.refreshRotates` / `refreshReuseRevokesFamily` / `expiry` / `logout` / `changePassword`, `JwtServiceTest`, `JwtAuthFilterTest` (versão antiga recusada); front: `client.test.ts` (nenhum token de sessão em `localStorage`/`sessionStorage`; um refresh por vez, inclusive entre abas); E2E: atributos do cookie, rotação e reuso através do nginx |
| Link de cancelar/remarcar vazado | Token de 256 bits (o mesmo da confirmação), só o SHA-256 no banco, escopo de um agendamento, vale até o fim do atendimento; vai no fragmento da URL (`/gerenciar#token`, nunca no servidor nem no Referer) e no corpo das chamadas; ações só dentro do prazo da política; remarcar passa o token para o agendamento novo e o antigo deixa de responder; a consulta não devolve telefone nem e-mail | `ManageLinkIntegrationTest` (`lookupAndCancel`, `tooLateAndExpiredLink`, `reschedule`, `rescheduleFailuresKeepBooking`, `isolation`) |
| Convite para a equipe reutilizado ou vazado | Código de 256 bits mostrado uma vez, só o SHA-256 no banco, uso único, expira em 72 h, revogável (novo convite revoga o anterior); vai no fragmento da URL (`/convite#código`, nunca enviado ao servidor nem no Referer) e no corpo do POST; antes do login fica só em `sessionStorage` desta aba (some ao aceitar, recusar, sair da conta ou fechar a aba); resposta igual para código inválido, usado, revogado ou vencido; aceitar passa por função `SECURITY DEFINER` com o usuário da transação (a política de `memberships` continua barrando entrada direta) | `InviteIntegrationTest` (fluxo, `invalidCodes`, `concurrentAccept`), `InviteRowLevelSecurityTest` |
| Profissional vendo ou mexendo na agenda de outro (dados de clientes); ex-funcionário | Agenda, agendamentos, status e bloqueios só do profissional **ativo** vinculado à conta (ADR-010); o dono, em todos; membro sem vínculo, em nenhum; o dono tira a conta da equipe (`DELETE .../account`) e o vínculo some pela FK; ao agendar com telefone de cliente existente, o profissional não recebe o e-mail guardado | `InviteIntegrationTest.ownAgendaBookings` / `ownTimeOff` / `inactiveProfessional` / `removeAccount` / `customerEmailNotLeaked`, `ActorTest` |
| Campo extra no JSON (mass assignment) | `fail-on-unknown-properties`; DTOs separados das entidades | `AuthIntegrationTest.registerValidation` (`role` extra → 400); E2E; `ArchitectureTest.noEntitiesInApi` |
| Injeção (SQL, HTML, cabeçalho de e-mail) | Só consultas parametrizadas; texto sem caracteres de controle; e-mails com conteúdo escapado | `AuthIntegrationTest.registerValidation` (quebra de linha, NUL e U+2028 recusados); consultas parametrizadas (CodeQL); e-mails: fase 3 |
| XSS | React sem `dangerouslySetInnerHTML`; CSP `script-src 'self'`; token fora do `localStorage` | E2E (CSP `script-src 'self'`, `frame-ancestors 'none'`); `client.test.ts` (token fora do armazenamento do navegador) |
| CSRF | Rotas da API com `Authorization` (não cookie); a única rota com cookie (refresh) exige `SameSite=Strict` + cabeçalho próprio. Por isso o CSRF do Spring Security fica desligado: o token dele exigiria um cookie legível pelo JavaScript só para proteger uma rota já protegida (alerta `java/spring-disabled-csrf-protection` do CodeQL dispensado como falso positivo, PR #1) | `AuthIntegrationTest.refreshRotates` (refresh sem `X-Requested-With` → 403), `SecurityHardeningIntegrationTest.cors`; E2E |
| Erro vazando detalhe interno | Respostas de erro padronizadas, sem stack trace, SQL ou classe | `GlobalExceptionHandlerTest`, `SecurityHardeningIntegrationTest.errorsDoNotLeak` |
| Configuração insegura em produção | A API não sobe com segredo fraco, CORS aberto, banco errado ou usuário do banco com privilégio de dono | `ProductionConfigValidatorTest` (inclui senha do Redis e cookie `Secure`) |
| Falha de SQL virar controle do banco | Três usuários no PostgreSQL: superusuário só cria; dono só migra; API só DML | `RowLevelSecurityTest.appUserIsUnprivileged` (a API conecta sem superusuário nem BYPASSRLS); CI sobe a produção com os três usuários |
| Dependência ou imagem vulnerável | Dependabot (versões e alertas), Trivy no CI barrando HIGH/CRITICAL corrigíveis, CodeQL, Actions e imagens fixadas por hash | CI: Trivy nas imagens (falha em HIGH/CRITICAL corrigível), CodeQL, Dependabot |
| Segredo commitado | Varredura de segredos + proteção de push do GitHub; `.env*` no `.gitignore` | `.gitignore`; varredura de segredos e proteção de push: ativar nas configurações do GitHub |
| Dados pessoais em log ou trace | Logs sem e-mail/telefone (mascarados), traces sem corpo de requisição | parcial: logs da fase 0 usam só ids (nunca e-mail); teste de log: fase 2 |

## Eventos (fase 3a)

- O RabbitMQ fica só na rede interna (`data`), com usuário e senha próprios (o validador de produção exige senha
  aleatória de 16+ caracteres; o `guest` não existe). Filas *quorum* em disco.
- O conteúdo do aviso (e-mail do cliente, texto com o link pessoal) fica no `outbox_events` só até ser publicado: ao
  marcar como publicado, o `payload` vira nulo (`CHECK` garante). O `outbox_events` tem RLS por estabelecimento; o
  publicador lê por funções `SECURITY DEFINER` que só pegam e marcam.
- Na fila, a mensagem fica até ser consumida (segundos; até 3 h com o provedor fora); na fila de falhas e na dos não
  roteados, no máximo 7 dias (TTL e teto de 10 mil). Risco aceito: nesse tempo o link pessoal (token em claro) e o
  e-mail ficam no disco do RabbitMQ, na rede interna e no volume dele — fora do alcance da anonimização (LGPD). O
  código de confirmação nunca passa pelo outbox (emenda ao ADR-003).
- Container do RabbitMQ sem root (usuário da imagem), sem capacidades, sistema de arquivos somente leitura (dados no
  volume), memória limitada com alarme abaixo do limite (`deploy/rabbitmq/90-agendai.conf`). Portas internas (métricas,
  distribuição) só na rede `data`.
- `processed_events` não tem `tenant_id` nem RLS, de propósito: guarda só o id do evento e a hora (nenhum dado de
  estabelecimento ou cliente).
- Pendentes (3b): usuários separados para publicar e consumir (hoje o mesmo, com acesso a tudo no vhost); EXECUTE de
  `outbox_claim`/`outbox_published` restrito ao papel da API (junto com o das V7/V9).
- Testes: `OutboxIntegrationTest` (RabbitMQ fora → o aviso sai quando volta; duplicado → um e-mail; evento travado
  por outra instância → pulado, e o livre ao lado sai na hora; malformado → fila de falhas; fila de espera devolve;
  sem fila → não roteados), `OutboxRowLevelSecurityTest` (RLS; aviso desfeito com a transação; fora de transação,
  recusado), `EmailWorkerTest` (espera × fila de falhas), `ManageLinkIntegrationTest.noticeFailureKeepsResult`
  (provedor fora por mais que uma tentativa) e a regra ArchUnit "RabbitMQ só pelo `notification`".

## Riscos aceitos (revisão da fase 0)

| Risco | Por que aceito agora | Mitigação atual | Quando resolver |
|---|---|---|---|
| Descobrir se um e-mail tem conta: o cadastro responde 409 e uma conta bloqueada responde 429 (e-mail inexistente nunca bloqueia) | Cadastro sem confirmação por e-mail precisa dizer que o e-mail já existe; esconder exige o fluxo de e-mail | Limite por IP no Redis (20/min) e no nginx (10/min) | Fase 3 (e-mail): confirmação de cadastro e resposta única |
| Bloqueio de conta como negação de serviço: quem sabe o e-mail do dono pode mantê-lo bloqueado (até 24 h por vez) | É o preço do bloqueio por conta contra força bruta distribuída | Teto de 24 h; o dono continua logado nos aparelhos com sessão (o refresh não passa pelo bloqueio) | Fase 3: desbloqueio por link no e-mail |

Corrigido na mesma revisão: a política de INSERT em `memberships` aceitava o próprio usuário em **qualquer**
estabelecimento (um bug futuro que deixasse o `tenant_id` vir do cliente bastaria). Agora só entra quem cria um
estabelecimento ainda sem membros; teste `RowLevelSecurityTest.cannotJoinForeignTenant`.

Fase 1c (agendamentos): horário duplo impossível no banco (restrição de exclusão, teste com 100 pedidos simultâneos);
RLS e FKs compostas também em `bookings` e `customers` (`BookingRowLevelSecurityTest`). Revisão: teto de 1000
agendamentos criados por estabelecimento em 24 h (criar e cancelar em laço não enche o banco); telefone já cadastrado
com outro nome é 409 sem revelar o nome (não vira consulta "telefone → cliente"); mudança de status atômica (dois
pedidos simultâneos não se sobrescrevem); `@PlainText` passa a barrar também controles fora do ASCII (NEL) e
caracteres invisíveis de formatação (inversão de sentido), e vale para o e-mail do cliente. Desde a 1d, o profissional
da equipe só age na própria agenda (linha "Profissional vendo ou mexendo na agenda de outro", acima).

Fase 2b (agendar pela página): e-mail fora do ar → 503 e nada fica (a transação desfaz o pendente;
`PublicReservationIntegrationTest.mailDown`); o e-mail é texto puro; o código e o token nunca vão para log.
O e-mail do código não leva o nome digitado (é enviado pelo Agendaí a um endereço qualquer). Tetos por origem: os
agendamentos da página têm teto diário próprio e de pendentes abertos (50 por estabelecimento) — um robô não trava o
painel. Pendências: limite por e-mail de destino; o nome do estabelecimento (qualquer um cria um) aparece no e-mail —
barrar URL nos nomes; clientes de tentativas nunca confirmadas ficam guardados (resolvido na 2d: a retenção os anonimiza em 30 dias); EXECUTE da
função de expiração restrito ao papel da API (hoje vale o USAGE do schema, só dele).

Fase 1d (convite), riscos aceitos e pendências: o 409 "telefone com outro nome" confirma a um profissional da equipe
que aquele telefone é cliente do estabelecimento (sem revelar nome nem e-mail); o aceite de convite não tem limite por
IP (código de 256 bits, exige login); a página de aceite não mostra o nome do estabelecimento antes de aceitar
(prévia por hash, com limite, fica para depois); convites usados/vencidos/revogados ainda sem limpeza periódica.

Revisão da fase 1b (agenda): datas fora de uma janela razoável (listagem de 1 ano para trás a 2 para a frente; bloqueio
terminando em até 2 anos) viram 400 em vez de 500 com stack no log, e nenhum bloqueio "eterno" ocupa o teto dos
futuros; os horários livres só leem bloqueios ainda não terminados (no máximo 200), por maior que seja o histórico.
Pendente: limpeza dos bloqueios antigos (job com trava, junto com a de `refresh_tokens`).

## LGPD

- **Mínimo necessário:** do cliente, só nome, e-mail e telefone; nada de CPF.
- **Finalidade explícita** na página pública; consentimento separado para lembretes por WhatsApp.
- **Direitos do titular (2d):** o estabelecimento é o controlador; o cliente pede a ele, e só o **dono** responde, pela
  agenda: exportar (`GET /customers/{id}/export`, JSON com o cadastro e os agendamentos, inclusive o contato que a
  página registrou) e apagar (`DELETE /customers/{id}`). Apagar anonimiza: nome vira "Cliente removido", telefone e
  e-mail somem do cadastro; nos agendamentos somem o contato, a observação e o link pessoal; ficam horário, serviço,
  profissional, status e preço (agenda e caixa). Com agendamento por acontecer (pendente ou confirmado), 409 (cancele
  antes). O telefone liberado pode voltar num cadastro novo. Testes: `CustomerPrivacyIntegrationTest` (só o dono;
  outro estabelecimento 404; o link pessoal para de valer).
- **Titular certo:** a página liga o agendamento ao cadastro pelo telefone, e outra pessoa pode ter agendado com ele.
  A agenda mostra quem agendou e, quando difere, o cadastro (`registeredName`) — é sobre ele que exportar e apagar
  agem, e a confirmação nomeia o cadastro e o telefone. A exportação não entrega o contato de terceiro (nome e e-mail
  diferentes do cadastro): fica só a marca `otherContact`.
- **Apagar × agendar ao mesmo tempo:** apagar trava a linha do cadastro (`FOR UPDATE`) e quem agenda a lê com
  `FOR SHARE`: ou o agendamento termina antes e conta para o 409, ou chega depois e cria um cadastro novo — nunca fica
  ligado ao anonimizado (`bookWhileForgetting`).
- **O que fica depois de anonimizar:** e-mails já enviados (no provedor e na caixa do cliente), backups do banco até
  vencerem e logs de acesso com IP; nada disso sai pela API.
- **Retenção (2d):** um job (a cada 6 h, lotes de 500, função `anonymize_stale_customers` — V9, `SECURITY DEFINER`,
  `SKIP LOCKED`, relógio limitado ao `now()` do banco) anonimiza a tentativa pela página nunca confirmada depois de 30
  dias e o cliente sem agendamento há 24 meses.
- **Finalidade (2d):** o formulário da página diz para que os dados servem, por quanto tempo ficam e a quem pedir
  cópia ou exclusão.
- **Pendências (2d):** o dono só alcança um cliente pela agenda (até 366 dias para trás): o cliente com último
  agendamento entre 1 ano e 24 meses ainda não tem caminho pela interface (busca por telefone, só para o dono); EXECUTE
  de `anonymize_stale_customers` restrito ao papel da API (como o da V7: o nome do papel vem do ambiente; hoje vale o
  USAGE do schema).
- **Motivo de bloqueio** ("médico", "exame") pode ser dado de saúde da equipe: só o dono vê; o resto da equipe vê só
  o período (`ScheduleIntegrationTest.timeOffAccess`), e a página pública nunca o recebe.

## Processo

- Toda funcionalidade nova passa pelo agente `revisor-seguranca` antes do PR, e o resultado vai para a descrição do PR.
- Revisões de segurança completas ao fim de cada fase, registradas aqui (achados, correção e teste de cada um).
- Teste de mutação (PIT) obrigatório nos pacotes `security`, `auth` e nas regras de agendamento.
