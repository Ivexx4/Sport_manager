package pt.voleibol.gestao.identity;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import pt.voleibol.gestao.tenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantClaimValidatorTest {

    private static final UUID CLUBE_A = UUID.fromString("00000000-0000-0000-0000-00000000aaaa");
    private static final UUID CLUBE_B = UUID.fromString("00000000-0000-0000-0000-00000000bbbb");

    private final TenantClaimValidator validator = new TenantClaimValidator();

    @AfterEach
    void limpar() {
        TenantContext.clear();
    }

    @Test
    void tokenDoClubeAtualEValido() {
        TenantContext.set(CLUBE_A);
        Jwt jwt = jwtWithClaim(CLUBE_A.toString());

        assertThat(validator.validate(jwt).hasErrors()).isFalse();
    }

    @Test
    void tokenDeOutroClubeEInvalido() {
        TenantContext.set(CLUBE_B);
        Jwt jwt = jwtWithClaim(CLUBE_A.toString());

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).extracting("errorCode").containsExactly("invalid_token");
    }

    @Test
    void semContextoDeClubeEInvalido() {
        Jwt jwt = jwtWithClaim(CLUBE_A.toString());

        assertThat(validator.validate(jwt).hasErrors()).isTrue();
    }

    @Test
    void tokenSemClaimDeClubeEInvalido() {
        TenantContext.set(CLUBE_A);
        Jwt jwt = mock(Jwt.class);
        when(jwt.getClaimAsString(TenantClaimValidator.CLAIM)).thenReturn(null);

        assertThat(validator.validate(jwt).hasErrors()).isTrue();
    }

    @Test
    void desafioDeMfaNaoServeComoTokenDeAcesso() {
        TenantContext.set(CLUBE_A);
        Jwt jwt = mock(Jwt.class);
        when(jwt.getClaimAsString(TenantClaimValidator.CLAIM)).thenReturn(CLUBE_A.toString());
        when(jwt.getClaimAsString(MfaService.PURPOSE_CLAIM)).thenReturn(MfaService.PURPOSE_VALUE);

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).extracting("errorCode").containsExactly("invalid_token");
    }

    private static Jwt jwtWithClaim(String value) {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getClaimAsString(TenantClaimValidator.CLAIM)).thenReturn(value);
        return jwt;
    }
}
