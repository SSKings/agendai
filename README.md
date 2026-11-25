# Agendaí

Agendamento online para pequenos negócios — barbearias, salões, clínicas, estúdios. O estabelecimento publica um link,
o cliente escolhe o serviço, o profissional e o horário em segundos, sem criar conta, e recebe lembretes antes do
atendimento. Cancelou? A vaga vai sozinha para quem está na lista de espera.

> **Situação:** fase 0 (fundação) — login da equipe, estabelecimento com isolamento em três camadas e toda a rede de
> proteção no CI. Ver [docs/roadmap.md](docs/roadmap.md).

## Princípios (não negociáveis)

1. **Todo código entra com testes.** Desenvolvimento orientado a testes (TDD); a meta é **100% de cobertura** de
   linhas e de ramos, exigida pelo CI. Exceções só com justificativa registrada. Ver [docs/testes.md](docs/testes.md).
2. **Segurança desde o primeiro commit.** Cada controle de segurança tem um teste que falha sem ele. Ver
   [docs/seguranca.md](docs/seguranca.md).
3. **Escalabilidade.** API sem estado, pronta para várias instâncias; nada que funcione só com uma.
4. **Desempenho medido, não suposto.** Metas de latência (SLOs) e testes de carga com números no CI.

## Documentação

| Documento | Conteúdo |
|---|---|
| [docs/produto.md](docs/produto.md) | Problema, pessoas, funcionalidades e critérios de aceite do MVP |
| [docs/arquitetura.md](docs/arquitetura.md) | Componentes, modelo de dados, concorrência, eventos, escalabilidade e desempenho |
| [docs/seguranca.md](docs/seguranca.md) | Modelo de ameaças, controles e o teste de cada um |
| [docs/testes.md](docs/testes.md) | Política de testes: TDD, 100% de cobertura, mutação, concorrência, carga |
| [docs/roadmap.md](docs/roadmap.md) | Fases do projeto, entregas e critérios de pronto |
| [docs/adr.md](docs/adr.md) | Decisões de arquitetura e o porquê de cada uma |
| [CLAUDE.md](CLAUDE.md) | Regras de trabalho para o desenvolvimento assistido (Claude Code) |

## Rodando localmente

Pré-requisitos: Java 17, Node 22+ e Docker.

```bash
cd backend && ./mvnw spring-boot:run     # sobe PostgreSQL (com os três usuários) e Redis do compose.yaml; API em :8080
cd frontend && npm ci && npm run dev     # http://localhost:5173 (chama a API pelo proxy /api)
```

Documentação interativa da API (só em dev): http://localhost:8080/swagger-ui.html

Pilha de produção (nginx com TLS, API, PostgreSQL, Redis), como o CI sobe: veja o cabeçalho de
[deploy/docker-compose.prod.yml](deploy/docker-compose.prod.yml) e o teste ponta a ponta
[deploy/e2e-smoke.py](deploy/e2e-smoke.py).

## Stack

**Backend:** Java 17 · Spring Boot 4 · Spring Security · Spring Data JPA · Flyway · PostgreSQL (com RLS) · Redis ·
RabbitMQ (fase 3) · Testcontainers · ArchUnit · JaCoCo · PIT
**Frontend:** React · TypeScript · Vite · Tailwind CSS · Vitest · Testing Library (tipos gerados do contrato OpenAPI)
**Infra:** Docker · nginx · GitHub Actions · Trivy · CodeQL · Terraform (AWS) · OpenTelemetry · k6

## Como contribuir

- Trabalho em branch própria (de preferência num `git worktree`), sempre por PR — a `main` é protegida.
- Um PR só entra com todos os checks verdes: testes e cobertura, mutação, contrato da API, migrações, pilha de
  produção (com upgrade sobre a versão anterior), scan de imagens e CodeQL.
- Toda mudança de comportamento atualiza a documentação correspondente no mesmo PR.
