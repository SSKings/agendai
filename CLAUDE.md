# Agendaí — regras de trabalho

Leia antes de qualquer tarefa: [README.md](README.md), [docs/roadmap.md](docs/roadmap.md) e o documento da área que
você vai mexer (produto, arquitetura, segurança, testes, ADRs).

## Prioridades (nesta ordem)

1. **Testes:** nada entra sem teste. TDD — escreva o teste, veja-o falhar pelo motivo certo, depois implemente.
   Meta de **100% de cobertura** de linhas e ramos (o CI exige); mutação (PIT) ≥ 95% no domínio e na segurança.
2. **Segurança:** todo controle tem um teste que falha sem ele. Dúvida entre conveniência e segurança → segurança.
3. **Escalabilidade:** nada de estado local que importe entre requisições (use PostgreSQL ou Redis); jobs com trava
   distribuída; consultas paginadas.
4. **Desempenho:** respeite os SLOs de docs/arquitetura.md; consulta nova vem com índice e, se crítica, com teste de
   carga.

## Como trabalhar

- Fale com o usuário em **português do Brasil**.
- **Nunca** trabalhe na `main` nem troque a branch do checkout do usuário: crie um `git worktree` em
  `~/Projetos-wt/agendai-<tarefa>` a partir de `origin/main` (nunca em `/tmp`).
- **Commit, push e PR só quando o usuário pedir.** Mensagens de commit em português, explicando o porquê.
- Correção de bug começa por um teste que reproduz o bug.
- Ao terminar uma funcionalidade, antes de propor o PR, rode os agentes revisores (em `.claude/agents/`):
  `guardiao-testes`, `revisor-seguranca`, `revisor-desempenho` e, se mexeu em fronteiras de módulos ou em decisões,
  `revisor-arquitetura`. Corrija o que for real e registre o resultado na descrição do PR.
- Mudou a API → atualize o contrato (`docs/openapi.json`) e regere os tipos do front.
- Nunca edite uma migração já mergeada: crie a próxima.
- Mudou comportamento, decisão ou controle → atualize a documentação correspondente no mesmo PR.
- Nunca commite segredos (`.env*`, certificados, tokens). Dados pessoais de verdade nunca entram em teste.

## Comandos

```bash
cd backend && ./mvnw verify                     # testes + cobertura (100%)
cd backend && ./mvnw pitest:mutationCoverage    # mutação
cd frontend && npm run coverage && npx tsc -b && npm run lint && npm run build
```

(Os comandos passam a valer a partir da fase 0.)
