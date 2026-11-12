-- Provisionamento de clubes, outbox de e-mail e permissões adicionais.

-- A aplicação passa a poder criar/atualizar clubes (provisionamento via API de plataforma).
GRANT INSERT, UPDATE ON tenant TO volei_app;

-- Outbox de e-mails: tabela de plataforma (sem tenant_id/RLS), processada de forma assíncrona
-- com novas tentativas, para garantir entrega fiável mesmo após falhas temporárias.
CREATE TABLE email_outbox (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient       text        NOT NULL,
    subject         text        NOT NULL,
    body            text        NOT NULL,
    status          text        NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts        int         NOT NULL DEFAULT 0,
    max_attempts    int         NOT NULL DEFAULT 5,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error      text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    sent_at         timestamptz
);

CREATE INDEX email_outbox_pending_ix ON email_outbox (next_attempt_at)
    WHERE status = 'PENDING';

GRANT SELECT, INSERT, UPDATE, DELETE ON email_outbox TO volei_app;
