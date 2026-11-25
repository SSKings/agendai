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
| Ver ou alterar dados de outro estabelecimento | Guard de acesso a cada requisição + consultas filtradas por `tenant_id` + FKs compostas + **Row-Level Security** no PostgreSQL | Teste de isolamento varrendo **todas** as rotas com ids de outro estabelecimento; teste que desliga o guard e confirma que o RLS ainda barra. Hoje: `TenantIsolationTest`, `RowLevelSecurityTest` (sem guard, como `agendai_app`), `TenantAccessTest`; catálogo: `CatalogIntegrationTest.isolation` / `onlyOwnerWrites` (só o dono altera) e `CatalogRowLevelSecurityTest` (RLS sozinho e FK composta impedindo ligar dados de estabelecimentos diferentes) |
| Agendamento falso em nome de outra pessoa / spam | Confirmação por código de 6 dígitos no e-mail (só o hash, 10 min, 5 tentativas) | fase 2 |
| Robô esgotando a agenda com reservas temporárias | Limite por IP e por estabelecimento **no Redis** (vale entre instâncias); reservas expiram em 5 min; teto de reservas abertas por IP | Limite por IP no Redis: `RateLimitIntegrationTest`, `RateLimitFilterTest`. Reservas e teto por IP: fase 2 |
| Força bruta no login da equipe | Bloqueio progressivo por conta, limite por IP (IPv6 por /64), mesma resposta para e-mail inexistente | `AuthIntegrationTest.lockout` / `lockoutCap` / `login` (mesma resposta para e-mail inexistente), `RateLimitIntegrationTest.limitPerIp` / `ipv6Block`, `RateLimitFilterTest` (Redis fora: não derruba a API) |
| Senha fraca | Política com lista de senhas comuns e disfarces (`s3nh@`), sem nome/e-mail | `PasswordStrengthTest`, `AuthIntegrationTest.registerValidation` |
| Sessão roubada | Access token curto (15 min) **só em memória** no front + refresh token em cookie `HttpOnly; Secure; SameSite=Strict` com rotação e detecção de reuso; logout revoga tudo (ADR-005) | `AuthIntegrationTest.refreshRotates` / `refreshReuseRevokesFamily` / `expiry` / `logout` / `changePassword`, `JwtServiceTest`, `JwtAuthFilterTest` (versão antiga recusada); front: `client.test.ts` (nada em `localStorage`/`sessionStorage`; um refresh por vez, inclusive entre abas); E2E: atributos do cookie, rotação e reuso através do nginx |
| Link de cancelar/remarcar vazado | Token de 256 bits, só o hash no banco, escopo de um agendamento, expira após o horário; ações sensíveis pedem confirmação | fase 2 |
| Convite para a equipe reutilizado | Código de uso único, hash no banco, expira em 72 h, revogável | fase 1 |
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

## LGPD

- **Mínimo necessário:** do cliente, só nome, e-mail e telefone; nada de CPF.
- **Finalidade explícita** na página pública; consentimento separado para lembretes por WhatsApp.
- **Direitos do titular:** o cliente pode pedir exportação e exclusão; exclusão anonimiza e mantém só o necessário
  para a agenda do estabelecimento (horário, serviço).
- **Retenção:** dados de clientes sem agendamento há 24 meses são anonimizados por um job.

## Processo

- Toda funcionalidade nova passa pelo agente `revisor-seguranca` antes do PR, e o resultado vai para a descrição do PR.
- Revisões de segurança completas ao fim de cada fase, registradas aqui (achados, correção e teste de cada um).
- Teste de mutação (PIT) obrigatório nos pacotes `security`, `auth` e nas regras de agendamento.
