package pt.voleibol.gestao.athletes;

import java.io.BufferedReader;
import java.io.StringReader;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;


import pt.voleibol.gestao.shared.CurrentUser;
import pt.voleibol.gestao.shared.Role;
import pt.voleibol.gestao.shared.Rules;
import pt.voleibol.gestao.tenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AthleteServiceUnitTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000012");

    @AfterEach
    void limpaTenantContext() {
        TenantContext.clear();
    }

    @Test
    void importCsvAceitaLinhasValidasEIgnoraLinhasInvalidas() throws Exception {
        AthleteRepository repository = mock(AthleteRepository.class);
        AthleteService service = new AthleteService(repository, null, null);
        TenantContext.set(TENANT);

        when(repository.licenseExists(eq(TENANT), anyString(), eq(null)))
                .thenAnswer(invocation -> "LIC-DUP".equals(invocation.getArgument(1)));
        doNothing().when(repository).insert(any(), eq(TENANT), any(AthleteRepository.Data.class), eq(ACTOR));
        doThrow(new DuplicateKeyException("duplicate"))
                .when(repository).insert(any(), eq(TENANT),
                        eq(new AthleteRepository.Data("Duplicado", LocalDate.of(2010, 1, 1), "M",
                                null, null, null, "LIC-INSERT", "ACTIVE", null)), eq(ACTOR));

        String csv = """
                fullName,birthDate,gender,nif,email,phone,licenseNumber,status
                too-short
                ,2010-01-01,F
                Invalido,nao-e-data,F
                Valido,2010-01-01,X,,,,,BAD
                Licenca repetida,2010-01-01,F,,,,LIC-DUP,ACTIVE
                Duplicado,2010-01-01,M,,,,LIC-INSERT,ACTIVE
                Segundo,2011-02-02,m,,,,LIC-2,INACTIVE
                """;

        int imported = service.importCsv(user(Role.ADMIN), new StringReader(csv));

        assertThat(imported).isEqualTo(2);
    }

    @Test
    void importCsvTambemAceitaBufferedReader() throws Exception {
        AthleteRepository repository = mock(AthleteRepository.class);
        AthleteService service = new AthleteService(repository, null, null);
        TenantContext.set(TENANT);
        doNothing().when(repository).insert(any(), eq(TENANT), any(AthleteRepository.Data.class), eq(ACTOR));

        int imported = service.importCsv(user(Role.DIRECAO),
                new BufferedReader(new StringReader("Nome,2010-01-01,F")));

        assertThat(imported).isEqualTo(1);
    }

    @Test
    void normalizaDadosEEstados() {
        AthleteRepository.Data data = AthleteService.data("  Ana  ", LocalDate.of(2010, 1, 2), "f",
                " 123 ", " ANA@EXAMPLE.PT ", " 910 ", " LIC ", null, null);

        assertThat(data.fullName()).isEqualTo("Ana");
        assertThat(data.gender()).isEqualTo("F");
        assertThat(data.nif()).isEqualTo("123");
        assertThat(data.email()).isEqualTo("ana@example.pt");
        assertThat(data.phone()).isEqualTo("910");
        assertThat(data.licenseNumber()).isEqualTo("LIC");
        assertThat(data.status()).isEqualTo("ACTIVE");
        assertThat(AthleteService.data("Ana", data.birthDate(), "M", null, "", " ", null,
                "inactive", ACTOR).status()).isEqualTo("INACTIVE");
        assertThatThrownBy(() -> Rules.parse(AthleteStatus.class, "pending", "Estado"))
                .isInstanceOf(RuntimeException.class);
    }

    private static CurrentUser user(Role... roles) {
        return new CurrentUser(ACTOR, Set.of(roles));
    }
}
