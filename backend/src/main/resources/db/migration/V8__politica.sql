-- Fase 2c: política do estabelecimento para o cliente (página pública e link pessoal). O painel não segue estes
-- prazos (o dono agenda e cancela quando precisar); o teto técnico de 180 dias continua valendo para todos (ADR-008).
ALTER TABLE tenants
    -- antecedência mínima para o cliente agendar pela página (ex.: 60 = nada para daqui a menos de 1 h)
    ADD COLUMN min_notice_minutes  INT NOT NULL DEFAULT 60 CHECK (min_notice_minutes BETWEEN 0 AND 10080 AND min_notice_minutes % 5 = 0),
    -- até quantas horas antes o cliente cancela ou remarca sozinho pelo link pessoal
    ADD COLUMN cancel_until_hours  INT NOT NULL DEFAULT 24 CHECK (cancel_until_hours BETWEEN 0 AND 720),
    -- quantos dias à frente a agenda abre para o cliente
    ADD COLUMN booking_window_days INT NOT NULL DEFAULT 60 CHECK (booking_window_days BETWEEN 1 AND 180);

-- quantas vezes o cliente já remarcou (o novo herda do antigo + 1): até 3, para o link não virar gerador de linhas
ALTER TABLE bookings ADD COLUMN reschedules INT NOT NULL DEFAULT 0 CHECK (reschedules BETWEEN 0 AND 3);
