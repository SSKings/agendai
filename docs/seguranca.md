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
| Link de cancelar/remarcar vazado | Token de 256 bits, só o hash no banco, escopo de um agendamento, expira após o horário; ações sensíveis pedem confirmação | fase 2 |
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
barrar URL nos nomes; clientes de tentativas nunca confirmadas ficam guardados (anonimizar na LGPD da 2c); EXECUTE da
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
- **Direitos do titular:** o cliente pode pedir exportação e exclusão; exclusão anonimiza e mantém só o necessário
  para a agenda do estabelecimento (horário, serviço).
- **Retenção:** dados de clientes sem agendamento há 24 meses são anonimizados por um job.
- **Motivo de bloqueio** ("médico", "exame") pode ser dado de saúde da equipe: só o dono vê; o resto da equipe vê só
  o período (`ScheduleIntegrationTest.timeOffAccess`), e a página pública nunca o recebe.

## Processo

- Toda funcionalidade nova passa pelo agente `revisor-seguranca` antes do PR, e o resultado vai para a descrição do PR.
- Revisões de segurança completas ao fim de cada fase, registradas aqui (achados, correção e teste de cada um).
- Teste de mutação (PIT) obrigatório nos pacotes `security`, `auth` e nas regras de agendamento.
