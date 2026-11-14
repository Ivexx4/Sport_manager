-- Evita repetir o alerta de caducidade de um documento todos os dias enquanto este está
-- dentro da janela de 30 dias. O alerta volta a ser enviado após 7 dias (lembrete semanal)
-- ou imediatamente quando o documento é atualizado (nova validade).
ALTER TABLE document ADD COLUMN expiry_notified_at timestamptz;
