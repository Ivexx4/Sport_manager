-- Limpeza periódica de dados que já não podem ser usados:
--   * convites expirados, aceites ou revogados;
--   * tokens de reposição de password usados ou expirados;
--   * refresh tokens expirados ou revogados;
--   * mensagens SENT/FAILED antigas da outbox de e-mail.
--
-- Executa com privilégios do dono (volei_migrator, BYPASSRLS) porque precisa de apagar em todos os
-- clubes numa só chamada; a aplicação nunca contorna o RLS diretamente, apenas chama esta função.
-- Os limites recebidos são limitados a "agora": a função nunca apaga dados que ainda não estejam
-- expirados/usados, mesmo que a aplicação peça uma retenção futura.

CREATE FUNCTION purge_expired_data(
    p_invitations_before timestamptz,
    p_resets_before timestamptz,
    p_refresh_tokens_before timestamptz,
    p_outbox_before timestamptz
) RETURNS TABLE(invitations_deleted bigint,
                resets_deleted bigint,
                refresh_tokens_deleted bigint,
                outbox_deleted bigint)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_invitations_before timestamptz;
    v_resets_before timestamptz;
    v_refresh_tokens_before timestamptz;
    v_outbox_before timestamptz;
    v_invitations bigint;
    v_resets bigint;
    v_refresh_tokens bigint;
    v_outbox bigint;
BEGIN
    v_invitations_before := COALESCE(LEAST(p_invitations_before, now()), now());
    v_resets_before := COALESCE(LEAST(p_resets_before, now()), now());
    v_refresh_tokens_before := COALESCE(LEAST(p_refresh_tokens_before, now()), now());
    v_outbox_before := COALESCE(LEAST(p_outbox_before, now()), now());

    DELETE FROM invitation
    WHERE expires_at < v_invitations_before
       OR accepted_at < v_invitations_before
       OR revoked_at < v_invitations_before;
    GET DIAGNOSTICS v_invitations = ROW_COUNT;

    DELETE FROM password_reset_token
    WHERE expires_at < v_resets_before
       OR used_at < v_resets_before;
    GET DIAGNOSTICS v_resets = ROW_COUNT;

    DELETE FROM refresh_token
    WHERE expires_at < v_refresh_tokens_before
       OR revoked_at < v_refresh_tokens_before;
    GET DIAGNOSTICS v_refresh_tokens = ROW_COUNT;

    DELETE FROM email_outbox
    WHERE status IN ('SENT', 'FAILED')
      AND COALESCE(sent_at, created_at) < v_outbox_before;
    GET DIAGNOSTICS v_outbox = ROW_COUNT;

    invitations_deleted := v_invitations;
    resets_deleted := v_resets;
    refresh_tokens_deleted := v_refresh_tokens;
    outbox_deleted := v_outbox;
    RETURN NEXT;
END;
$$;

REVOKE ALL ON FUNCTION purge_expired_data(timestamptz, timestamptz, timestamptz, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION purge_expired_data(timestamptz, timestamptz, timestamptz, timestamptz) TO volei_app;

-- Índices que apoiam a limpeza (a aplicação continua a ler pelos índices existentes).
CREATE INDEX invitation_expires_at_ix ON invitation (expires_at);
CREATE INDEX password_reset_token_expires_at_ix ON password_reset_token (expires_at);
CREATE INDEX refresh_token_expires_at_ix ON refresh_token (expires_at);
CREATE INDEX email_outbox_cleanup_ix ON email_outbox (COALESCE(sent_at, created_at))
    WHERE status IN ('SENT', 'FAILED');
