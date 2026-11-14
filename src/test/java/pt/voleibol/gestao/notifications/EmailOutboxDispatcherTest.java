package pt.voleibol.gestao.notifications;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.RowMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailOutboxDispatcherTest {

    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
    @SuppressWarnings("unchecked")
    private final JdbcClient.MappedQuerySpec<EmailOutboxDispatcher.Pending> query =
            mock(JdbcClient.MappedQuerySpec.class);
    private final MailTransport transport = mock(MailTransport.class);

    private final List<String> sql = new ArrayList<>();
    private final Map<String, Object> params = new LinkedHashMap<>();

    private EmailOutboxDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new EmailOutboxDispatcher(jdbc, transport);
        when(jdbc.sql(anyString())).thenAnswer(invocation -> {
            sql.add(invocation.getArgument(0));
            return statement;
        });
        when(statement.param(anyString(), any())).thenAnswer(invocation -> {
            params.put(invocation.getArgument(0), invocation.getArgument(1));
            return statement;
        });
        when(statement.query(any(RowMapper.class))).thenReturn(query);
        when(statement.update()).thenReturn(1);
        when(query.list()).thenReturn(List.of());
    }

    @Test
    void loteVazioNaoEnviaNemAtualiza() {
        dispatcher.dispatch();

        verify(transport, never()).deliver(anyString(), anyString(), anyString());
        assertThat(sql).noneMatch(s -> s.contains("UPDATE email_outbox"));
    }

    @Test
    void enviaComSucessoEMarcaComoEnviada() {
        UUID id = UUID.randomUUID();
        when(query.list()).thenReturn(List.of(pending(id, 0, 3)));
        when(statement.update()).thenReturn(1);

        dispatcher.dispatch();

        verify(transport).deliver("a@b.pt", "Assunto", "Corpo");
        assertThat(sql).anyMatch(s -> s.contains("status = 'SENT'"));
        assertThat(params).containsEntry("id", id);
    }

    @Test
    void falhaAgendaNovaTentativa() {
        UUID id = UUID.randomUUID();
        when(query.list()).thenReturn(List.of(pending(id, 0, 3)));
        doThrow(new RuntimeException("smtp em baixo"))
                .when(transport).deliver(anyString(), anyString(), anyString());

        dispatcher.dispatch();

        assertThat(params).containsEntry("attempts", 1)
                .containsEntry("status", "PENDING")
                .containsEntry("error", "smtp em baixo")
                .containsEntry("id", id);
        assertThat(params.get("next")).isInstanceOf(OffsetDateTime.class);
    }

    @Test
    void falhaNaUltimaTentativaFicaComoFalhadaETruncaOErro() {
        UUID id = UUID.randomUUID();
        when(query.list()).thenReturn(List.of(pending(id, 2, 3)));
        String erroLongo = "x".repeat(600);
        doThrow(new RuntimeException(erroLongo))
                .when(transport).deliver(anyString(), anyString(), anyString());

        dispatcher.dispatch();

        assertThat(params).containsEntry("attempts", 3)
                .containsEntry("status", "FAILED");
        assertThat((String) params.get("error")).hasSize(500);
    }

    @Test
    void erroSemMensagemNaoFalhaATrunca() {
        UUID id = UUID.randomUUID();
        when(query.list()).thenReturn(List.of(pending(id, 0, 3)));
        doThrow(new RuntimeException((String) null))
                .when(transport).deliver(anyString(), anyString(), anyString());

        dispatcher.dispatch();

        assertThat(params).containsEntry("status", "PENDING")
                .containsKey("error")
                .containsEntry("error", null);
    }

    @Test
    void mapeiaAsColunasDaConsultaParaOPendente() throws Exception {
        dispatcher.dispatch();

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<RowMapper<EmailOutboxDispatcher.Pending>> captor =
                org.mockito.ArgumentCaptor.forClass(RowMapper.class);
        verify(statement).query(captor.capture());

        UUID id = UUID.randomUUID();
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id", UUID.class)).thenReturn(id);
        when(rs.getString("recipient")).thenReturn("a@b.pt");
        when(rs.getString("subject")).thenReturn("Assunto");
        when(rs.getString("body")).thenReturn("Corpo");
        when(rs.getInt("attempts")).thenReturn(1);
        when(rs.getInt("max_attempts")).thenReturn(3);

        assertThat(captor.getValue().mapRow(rs, 0))
                .isEqualTo(new EmailOutboxDispatcher.Pending(id, "a@b.pt", "Assunto", "Corpo", 1, 3));
    }

    private static EmailOutboxDispatcher.Pending pending(UUID id, int attempts, int maxAttempts) {
        return new EmailOutboxDispatcher.Pending(id, "a@b.pt", "Assunto", "Corpo", attempts, maxAttempts);
    }
}
