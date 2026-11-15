package pt.voleibol.gestao.teams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import pt.voleibol.gestao.tenancy.TenantContext;

class SeasonServiceUnitTest {
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID ID = UUID.randomUUID();
    private static final SeasonRepository.SeasonRow ROW = new SeasonRepository.SeasonRow(
            ID, "2025/26", LocalDate.of(2025, 9, 1), LocalDate.of(2026, 6, 30), null, true,
            Instant.EPOCH, Instant.EPOCH);

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void validaAcessoTenantEPeriodo() {
        SeasonService service = new SeasonService(mock(SeasonRepository.class));
        assertStatus(() -> service.list(ACTOR, Set.of()), HttpStatus.FORBIDDEN);
        TenantContext.set(TENANT);
        assertStatus(() -> service.create(ACTOR, Set.of("MEMBER"), "x", null, null, null), HttpStatus.FORBIDDEN);
        assertStatus(() -> service.create(ACTOR, Set.of("ADMIN"), " ", null, null, null), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.create(ACTOR, Set.of("ADMIN"), "x",
                LocalDate.of(2026, 1, 1), LocalDate.of(2025, 1, 1), null), HttpStatus.BAD_REQUEST);
    }

    @Test
    void criaComDefaultsETraduzDuplicado() {
        TenantContext.set(TENANT);
        SeasonRepository repository = mock(SeasonRepository.class);
        when(repository.nameExists(TENANT, "Nova", null)).thenReturn(false);
        when(repository.findById(eq(TENANT), any())).thenReturn(Optional.of(ROW));
        SeasonService service = new SeasonService(repository);

        assertThat(service.create(ACTOR, Set.of("ADMIN"), " Nova ", null, null, null).name()).isEqualTo("2025/26");
        verify(repository).insert(any(), eq(TENANT), argThat(d -> d.isActive() && d.name().equals("Nova")), eq(ACTOR));

        when(repository.nameExists(TENANT, "Duplicada", null)).thenReturn(false);
        doThrow(new DuplicateKeyException("duplicate")).when(repository)
                .insert(any(), eq(TENANT), any(), eq(ACTOR));
        assertStatus(() -> service.create(ACTOR, Set.of("ADMIN"), "Duplicada", null, null, false),
                HttpStatus.CONFLICT);
    }

    @Test
    void atualizaCobreConflitoNaoEncontradoDuplicadoEDefaults() {
        TenantContext.set(TENANT);
        SeasonRepository repository = mock(SeasonRepository.class);
        when(repository.findById(TENANT, ID)).thenReturn(Optional.of(ROW));
        SeasonService service = new SeasonService(repository);

        when(repository.nameExists(TENANT, "Atualizada", ID)).thenReturn(false);
        when(repository.update(eq(TENANT), eq(ID), any())).thenReturn(true);
        when(repository.findById(TENANT, ID)).thenReturn(Optional.of(ROW));
        service.update(ACTOR, Set.of("DIRECAO"), ID, "Atualizada", null, null, null);
        verify(repository).update(eq(TENANT), eq(ID), argThat(d -> d.isActive()));

        when(repository.nameExists(TENANT, "Existe", ID)).thenReturn(true);
        assertStatus(() -> service.update(ACTOR, Set.of("ADMIN"), ID, "Existe", null, null, true),
                HttpStatus.CONFLICT);
        when(repository.nameExists(TENANT, "Gone", ID)).thenReturn(false);
        when(repository.update(eq(TENANT), eq(ID), any())).thenReturn(false);
        assertStatus(() -> service.update(ACTOR, Set.of("ADMIN"), ID, "Gone", null, null, true),
                HttpStatus.NOT_FOUND);
        doThrow(new DuplicateKeyException("duplicate")).when(repository)
                .update(eq(TENANT), eq(ID), any());
        assertStatus(() -> service.update(ACTOR, Set.of("ADMIN"), ID, "Outra", null, null, true),
                HttpStatus.CONFLICT);
    }

    @Test
    void removeEGetListCobremResultadosVazios() {
        TenantContext.set(TENANT);
        SeasonRepository repository = mock(SeasonRepository.class);
        when(repository.list(TENANT)).thenReturn(java.util.List.of(ROW));
        when(repository.findById(TENANT, ID)).thenReturn(Optional.empty());
        SeasonService service = new SeasonService(repository);

        assertThat(service.list(ACTOR, Set.of("MEMBER"))).hasSize(1);
        assertStatus(() -> service.get(ACTOR, Set.of("MEMBER"), ID), HttpStatus.NOT_FOUND);
        when(repository.anonymize(TENANT, ID)).thenReturn(false);
        assertStatus(() -> service.delete(ACTOR, Set.of("ADMIN"), ID), HttpStatus.NOT_FOUND);
        when(repository.anonymize(TENANT, ID)).thenReturn(true);
        service.delete(ACTOR, Set.of("ADMIN"), ID);
        verify(repository).audit(TENANT, ACTOR, "SEASON_ANONYMIZED", ID);
    }

    private static void assertStatus(org.junit.jupiter.api.function.Executable action, HttpStatus status) {
        try {
            action.execute();
            throw new AssertionError("Expected ResponseStatusException");
        } catch (ResponseStatusException e) {
            assertThat(e.getStatusCode()).isEqualTo(status);
        } catch (Throwable e) {
            throw new AssertionError(e);
        }
    }
}
