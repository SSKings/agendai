# Segurança

Regra de ouro: **cada controle desta página tem um teste que falha sem ele.** Controle sem teste não conta como feito.
A coluna "Teste" é preenchida conforme os controles forem implementados.

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
| Ver ou alterar dados de outro estabelecimento | Guard de acesso a cada requisição + consultas filtradas por `tenant_id` + FKs compostas + **Row-Level Security** no PostgreSQL | Teste de isolamento varrendo **todas** as rotas com ids de outro estabelecimento; teste que desliga o guard e confirma que o RLS ainda barra |
| Agendamento falso em nome de outra pessoa / spam | Confirmação por código de 6 dígitos no e-mail (só o hash, 10 min, 5 tentativas) | — |
| Robô esgotando a agenda com reservas temporárias | Limite por IP e por estabelecimento **no Redis** (vale entre instâncias); reservas expiram em 5 min; teto de reservas abertas por IP | — |
| Força bruta no login da equipe | Bloqueio progressivo por conta, limite por IP (IPv6 por /64), mesma resposta para e-mail inexistente | — |
| Senha fraca | Política com lista de senhas comuns e disfarces (`s3nh@`), sem nome/e-mail | — |
| Sessão roubada | Access token curto (15 min) **só em memória** no front + refresh token em cookie `HttpOnly; Secure; SameSite=Strict` com rotação e detecção de reuso; logout revoga tudo (ADR-005) | — |
| Link de cancelar/remarcar vazado | Token de 256 bits, só o hash no banco, escopo de um agendamento, expira após o horário; ações sensíveis pedem confirmação | — |
| Convite para a equipe reutilizado | Código de uso único, hash no banco, expira em 72 h, revogável | — |
| Campo extra no JSON (mass assignment) | `fail-on-unknown-properties`; DTOs separados das entidades | — |
| Injeção (SQL, HTML, cabeçalho de e-mail) | Só consultas parametrizadas; texto sem caracteres de controle; e-mails com conteúdo escapado | — |
| XSS | React sem `dangerouslySetInnerHTML`; CSP `script-src 'self'`; token fora do `localStorage` | — |
| CSRF | Rotas da API com `Authorization` (não cookie); a única rota com cookie (refresh) exige `SameSite=Strict` + cabeçalho próprio | — |
| Erro vazando detalhe interno | Respostas de erro padronizadas, sem stack trace, SQL ou classe | — |
| Configuração insegura em produção | A API não sobe com segredo fraco, CORS aberto, banco errado ou usuário do banco com privilégio de dono | — |
| Falha de SQL virar controle do banco | Três usuários no PostgreSQL: superusuário só cria; dono só migra; API só DML | — |
| Dependência ou imagem vulnerável | Dependabot (versões e alertas), Trivy no CI barrando HIGH/CRITICAL corrigíveis, CodeQL, Actions e imagens fixadas por hash | — |
| Segredo commitado | Varredura de segredos + proteção de push do GitHub; `.env*` no `.gitignore` | — |
| Dados pessoais em log ou trace | Logs sem e-mail/telefone (mascarados), traces sem corpo de requisição | — |

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
