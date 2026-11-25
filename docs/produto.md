# Produto

## Problema

Pequenos negócios de serviço agendam por WhatsApp, telefone ou caderno. O dono perde tempo respondendo "tem horário
amanhã?", erra na agenda, marca dois clientes no mesmo horário e perde dinheiro com faltas e cancelamentos de última
hora que deixam a cadeira vazia.

## Pessoas

| Pessoa | O que precisa |
|---|---|
| **Dono** do estabelecimento | Configurar serviços, profissionais e horários; ver a agenda de todos; reduzir faltas; saber a ocupação |
| **Profissional** | Ver a própria agenda do dia e da semana; bloquear horários (folga, almoço); marcar atendimento e falta |
| **Cliente** | Agendar em segundos pelo celular, sem criar conta; ser lembrado; cancelar ou remarcar sem ligar |

## Funcionalidades do MVP

### Estabelecimento (dono)

- Cadastro do estabelecimento: nome, link público (slug), fuso horário, endereço, contato.
- **Serviços:** nome, duração (múltiplos de 5 min), preço, intervalo de preparo após o atendimento, ativo/inativo.
- **Profissionais:** quem faz quais serviços; convite por link de uso único (72 h) para entrar na equipe: quem aceita,
  com a própria conta, vira profissional do estabelecimento vinculado àquele profissional. O profissional da equipe vê
  e mexe só na própria agenda (agendamentos, status, folgas e almoço); expediente, catálogo, feriados e convites são do
  dono, que também tira da equipe quem saiu (o profissional e os agendamentos ficam; o acesso, não). Limitação atual:
  quem não tem conta cria uma pelo cadastro, que também cria um estabelecimento próprio (cadastro só de profissional
  fica para depois).
- Limites por estabelecimento: até 100 serviços e 50 profissionais (cobre com folga o público-alvo; evita listas sem
  limite).
- **Expediente:** horários por dia da semana e por profissional (mais de uma faixa no dia = pausa, ex.: almoço; até
  28 faixas na semana); feriados (o estabelecimento inteiro) e bloqueios pontuais de um profissional, de até 92 dias
  cada e até 200 ainda por acontecer.
- **Horários livres:** começam de 15 em 15 minutos a partir do início de cada faixa; atendimento + preparo precisam
  caber inteiros na faixa e não encostar em bloqueio; hoje, só daqui para frente; consulta de hoje até 180 dias à
  frente. Serviço ou profissional inativo não tem horário.
- **Política:** antecedência mínima para agendar, até quantas horas antes o cliente pode cancelar sozinho, quantos
  dias à frente a agenda abre.

**Agendamento manual (painel):** qualquer pessoa da equipe escolhe um horário livre e informa nome, telefone (com DDD)
e, se quiser, e-mail e observação; o mesmo telefone é o mesmo cliente. Precisa caber no expediente, fora dos
bloqueios, de hoje (a partir de agora) até 180 dias; horário que não existe ou se repete na troca de horário de verão
é recusado. Telefone já cadastrado com outro nome é recusado (confira o número ou use o nome do cadastro). Na agenda do dia ou da semana: atendido e falta só depois do
início; cancelar libera o horário; status finais não mudam.

**Critérios de aceite:** horários livres respeitam expediente, bloqueios, duração + preparo e agendamentos
existentes; mudar o expediente não apaga agendamentos já feitos (eles aparecem como "fora do expediente" para
decisão do dono).

### Agendamento público (cliente)

- Página pública `agendai.app/{slug}`: serviço → profissional (ou "qualquer um") → dia → horário.
- **Reserva temporária:** ao escolher o horário, ele fica segurado por 5 minutos enquanto o cliente preenche nome,
  telefone e e-mail.
- **Confirmação por código** de 6 dígitos no e-mail (impede agendamento falso em nome de outra pessoa e spam).
- Link pessoal (de uso restrito, sem login) para **cancelar ou remarcar**, dentro da política.

**Critérios de aceite:** nunca dois agendamentos sobrepostos para o mesmo profissional, nem sob concorrência; reserva
temporária expirada libera o horário sozinha; o cliente nunca vê dados de outros clientes.

### Agenda (dono e profissionais)

- Visão do dia e da semana, por profissional; criar agendamento manual (cliente que ligou).
- Marcar atendido, falta (no-show) ou cancelado pelo estabelecimento (com aviso ao cliente).

### Notificações

- Confirmação do agendamento, lembrete 24 h e 2 h antes, cancelamento e remarcação.
- Canal inicial: e-mail. WhatsApp (API oficial) como canal adicional numa fase posterior.
- O cliente pode descadastrar lembretes; o aviso de cancelamento pelo estabelecimento é sempre enviado.

### Lista de espera

- Horário ocupado → o cliente entra na fila daquele dia/profissional/serviço.
- Cancelamento → a vaga é oferecida ao primeiro da fila por tempo limitado (ex.: 15 min); se ele não aceitar, passa ao
  próximo.

### Painel

- Ocupação da agenda, cancelamentos, faltas, serviços mais procurados, no período.

## Fora do MVP

Pagamento antecipado/sinal, app mobile nativo, múltiplas unidades por estabelecimento, avaliações, programa de
fidelidade, integração com Google Agenda. Ficam para depois e entram no roadmap só com decisão registrada.

## Métricas de sucesso do produto

- Tempo para o cliente concluir um agendamento (meta: < 60 s).
- Taxa de faltas antes × depois dos lembretes.
- Vagas de cancelamento preenchidas pela lista de espera.
