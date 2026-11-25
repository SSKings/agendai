// Mensagens da API (inglês, estáveis) → português. Mensagem desconhecida vira texto genérico: nada técnico na tela.

const MESSAGES: Record<string, string> = {
  'Invalid email or password': 'E-mail ou senha incorretos.',
  'Too many failed attempts. Try again later': 'Conta bloqueada por muitas tentativas. Tente de novo mais tarde.',
  'Too many requests': 'Muitas tentativas seguidas. Aguarde um minuto.',
  'Email already in use': 'Este e-mail já tem cadastro.',
  'Password is too common. Choose a less predictable one': 'Senha muito comum. Escolha uma menos previsível.',
  'Password must not contain your name, your email or the app name':
    'A senha não pode conter seu nome, seu e-mail ou o nome do app.',
  'Invalid password': 'Senha atual incorreta.',
  'New password must be different from the current one': 'A nova senha precisa ser diferente da atual.',
  'Authentication required': 'Sua sessão terminou. Entre de novo.',
  'Invalid session': 'Sua sessão terminou. Entre de novo.',
  'Validation failed': 'Confira os campos.',
  'Tenant not found': 'Estabelecimento não encontrado.',
  'Request body too large': 'Os dados enviados são grandes demais.',
  'Only owners can do this': 'Só o dono do estabelecimento pode fazer isso.',
  'Service not found': 'Serviço não encontrado.',
  'Service name already in use': 'Já existe um serviço com esse nome.',
  'Service limit reached': 'Limite de serviços atingido.',
  'Professional not found': 'Profissional não encontrado.',
  'Professional name already in use': 'Já existe um profissional com esse nome.',
  'Professional limit reached': 'Limite de profissionais atingido.',
  'Unknown service': 'Algum serviço escolhido não existe mais. Recarregue a página.',
  'Durations must be multiples of 5 minutes': 'Duração e intervalo vão de 5 em 5 minutos.',
  'End must be after start': 'O fim precisa ser depois do início.',
  'Overlapping working hours': 'Há faixas sobrepostas no mesmo dia.',
  'Times must be multiples of 5 minutes': 'Os horários vão de 5 em 5 minutos.',
  'Time off not found': 'Bloqueio não encontrado. Recarregue a página.',
  'Time off already ended': 'Esse período já terminou.',
  'Time off longer than 92 days': 'Um bloqueio pode durar no máximo 92 dias.',
  'Time off limit reached': 'Limite de bloqueios futuros atingido.',
  'Date out of range': 'Escolha uma data entre hoje e 180 dias à frente.',
  'Professional does not perform this service': 'Esse profissional não faz esse serviço.',
  'Booking not found': 'Agendamento não encontrado. Recarregue a página.',
  'Service or professional inactive': 'O serviço ou o profissional está inativo.',
  'Invalid phone': 'Telefone inválido: informe DDD e número.',
  'Start in the past': 'Esse horário já passou.',
  'Outside working hours': 'Fora do expediente ou num bloqueio do profissional.',
  'Time slot not available': 'Esse horário acabou de ser ocupado. Escolha outro.',
  'Invalid status change': 'Esse agendamento já foi finalizado.',
  'Booking has not started': 'Só dá para marcar atendido ou falta depois do horário de início.',
  'Booking limit reached': 'Limite de agendamentos do dia atingido. Tente mais tarde.',
  'Phone registered with another name': 'Esse telefone já está cadastrado com outro nome. Confira o número ou use o nome do cadastro.',
  'Time does not exist or repeats on this day': 'Esse horário não existe ou se repete nesse dia (horário de verão).',
  'Busy, try again': 'O sistema está ocupado agora. Tente de novo em instantes.',
  'Only your own agenda': 'Você só pode mexer na sua própria agenda.',
  'Professional already has an account': 'Esse profissional já tem conta vinculada.',
  'Invalid or expired invite': 'Convite inválido, já usado ou vencido. Peça um novo ao dono do estabelecimento.',
  'Already a member of this business': 'Você já faz parte deste estabelecimento.',
  'Professional is inactive': 'Ative o profissional antes de convidar.',
  'Professional has no linked account': 'Esse profissional não tem conta vinculada.',
}

export const GENERIC_ERROR = 'Algo deu errado. Tente de novo.'
export const OFFLINE = 'Sem conexão com o servidor. Confira a internet e tente de novo.'

export function translate(message: string | undefined): string {
  return (message && MESSAGES[message]) || GENERIC_ERROR
}

/** Mesma regra do backend (AuthDtos.PASSWORD_POLICY): 8 a 72 caracteres, com letras e números. */
export const PASSWORD_RULE = /^(?=.*\p{L})(?=.*\d).{8,72}$/u
export const PASSWORD_HINT = 'De 8 a 72 caracteres, com letras e números. Evite senhas comuns e seu nome.'
