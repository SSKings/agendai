# Política de testes

## Regras

1. **Nada entra sem teste.** Toda mudança de comportamento — funcionalidade, correção, controle de segurança, consulta
   nova — vem com o teste que a cobre, no mesmo PR.
2. **TDD:** o teste é escrito antes do código e precisa ser visto falhando pelo motivo certo.
3. **Meta: 100% de cobertura de linhas e de ramos**, no backend e no frontend. O CI exige 100%.
   - Exceções são raras e **explícitas**: ficam numa lista de exclusão no build (JaCoCo / Vitest), cada uma com um
     comentário dizendo por que não dá para testar (ex.: a classe `main`, código gerado). Exceção nova exige
     justificativa no PR.
   - Cobertura alta não basta: um teste que executa a linha sem verificar o resultado não protege nada. Por isso há
     teste de mutação.
4. **Teste de mutação (PIT)** no domínio e na segurança: mínimo de **95%** dos defeitos injetados pegos. Os
   sobreviventes restantes precisam ser equivalentes (não mudam comportamento) e são revisados a cada fase.
5. **Correção de bug começa por um teste que reproduz o bug** e falha no código antigo.
6. Testes rodam com a **JVM em UTC** (como o CI e o servidor).

## Tipos de teste

| Tipo | Ferramenta | O que cobre |
|---|---|---|
| Unidade | JUnit, AssertJ | Regras puras: cálculo de horários, política de cancelamento, tokens, senha |
| Integração | Spring Boot Test + **Testcontainers** (PostgreSQL, Redis, RabbitMQ **de verdade**) | Rotas, transações, restrições do banco, RLS, filas — nunca H2 nem mocks de infraestrutura |
| Concorrência | Threads/`CompletableFuture` contra o banco real | 100 pedidos simultâneos no mesmo horário → exatamente 1 agendamento |
| Resiliência | Testcontainers (pausar/derrubar o RabbitMQ ou o Redis) | Outbox não perde evento; worker idempotente; Redis fora → a API degrada sem quebrar o agendamento |
| Isolamento | Teste que percorre todas as rotas | Nenhuma rota devolve ou altera dado de outro estabelecimento |
| Contrato | OpenAPI gerado × `docs/openapi.json` + tipos TS gerados | API e front não divergem |
| Arquitetura | ArchUnit | Fronteiras entre módulos, nenhuma entidade exposta na API |
| Frontend | Vitest + Testing Library | Telas, fluxos e erros, com fixtures tipadas pelo contrato |
| Ponta a ponta | Pilha de produção no CI (nginx, API, worker, PostgreSQL, Redis, RabbitMQ) | Fluxo completo do agendamento e upgrade sobre a versão anterior com dados |
| Carga | k6 | SLOs de arquitetura.md como limites: o CI falha se a latência passar da meta |

## Convenções

- Nome do teste descreve o comportamento, em português (`@DisplayName`), ex.: *"Dois clientes no mesmo horário:
  só um consegue, o outro recebe 409"*.
- Um teste verifica um comportamento; nada de testes que "passam por acaso" (asserções fracas, `isNotNull` onde cabe o
  valor exato).
- Dados de teste montados por builders; nenhum teste depende da ordem de execução nem do relógio real (`Clock`
  injetado).

## Antes de abrir um PR

```bash
cd backend && ./mvnw verify                    # testes + cobertura 100%
cd backend && ./mvnw pitest:mutationCoverage   # mutação
cd frontend && npm run coverage && npx tsc -b && npm run lint
```

E o agente `guardiao-testes` revisa o diff procurando código sem teste e testes fracos.
