package pt.voleibol.gestao.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.web.server.ResponseStatusException;


import pt.voleibol.gestao.shared.AuditLog;
import pt.voleibol.gestao.shared.Rules;
import pt.voleibol.gestao.tenancy.TenantContext;

class AuthServiceUnitTest {
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void loginFalhaParaUtilizadorDesconhecidoPasswordInvalidaOuSemPapel() {
        TenantContext.set(TENANT);
        AuthRepository repository = mock(AuthRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("dummy");
        when(encoder.matches(anyString(), anyString())).thenReturn(true);
        JwtEncoder jwt = mock(JwtEncoder.class);
        AuditLog audit = mock(AuditLog.class);
        AuthService service = new AuthService(repository, mock(MfaService.class), encoder, jwt,
                new JwtProperties("secret", "issuer", Duration.ofMinutes(5), Duration.ofDays(1)), audit);

        assertThat(service.login("missing@example.test", "password")).isEmpty();
        verify(audit).record(TENANT, null, "LOGIN_FAILED");

        when(repository.findActiveUserByEmail("wrong@example.test"))
                .thenReturn(Optional.of(new AuthRepository.UserRow(USER, "hash")));
        when(encoder.matches(anyString(), eq("hash"))).thenReturn(false);
        assertThat(service.login("wrong@example.test", "password")).isEmpty();
        verify(audit).record(TENANT, USER, "LOGIN_FAILED");

        when(encoder.matches(anyString(), eq("hash"))).thenReturn(true);
        when(repository.findActiveRoles(TENANT, USER)).thenReturn(Set.of());
        assertThat(service.login("wrong@example.test", "password")).isEmpty();
    }

    @Test
    void loginComHashVazioUsaDummyEPasswordLongaFalha() {
        TenantContext.set(TENANT);
        AuthRepository repository = mock(AuthRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("dummy");
        when(repository.findActiveUserByEmail("user@example.test"))
                .thenReturn(Optional.of(new AuthRepository.UserRow(USER, " ")));
        when(encoder.matches(anyString(), eq("dummy"))).thenReturn(true);
        AuditLog audit = mock(AuditLog.class);
        AuthService service = new AuthService(repository, mock(MfaService.class), encoder, mock(JwtEncoder.class),
                new JwtProperties("secret", "issuer", Duration.ofMinutes(5), Duration.ofDays(1)), audit);

        assertThat(service.login("user@example.test", "a".repeat(100))).isEmpty();
        verify(audit).record(TENANT, USER, "LOGIN_FAILED");
    }

    @Test
    void loginBemSucedidoEmiteTokens() {
        TenantContext.set(TENANT);
        AuthRepository repository = mock(AuthRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtEncoder jwtEncoder = mock(JwtEncoder.class);
        when(encoder.encode(anyString())).thenReturn("dummy");
        when(repository.findActiveUserByEmail("user@example.test"))
                .thenReturn(Optional.of(new AuthRepository.UserRow(USER, "hash")));
        when(encoder.matches("password", "hash")).thenReturn(true);
        when(repository.findActiveRoles(TENANT, USER)).thenReturn(Set.of("ADMIN"));
        when(jwtEncoder.encode(any())).thenReturn(Jwt.withTokenValue("access")
                .header("alg", "none").claim("sub", USER.toString()).build());
        AuditLog audit = mock(AuditLog.class);
        AuthService service = new AuthService(repository, mock(MfaService.class), encoder, jwtEncoder,
                new JwtProperties("secret", "issuer", Duration.ofMinutes(5), Duration.ofDays(1)), audit);

        assertThat(service.login("user@example.test", "password")).isPresent();
        verify(audit).record(TENANT, USER, "LOGIN_SUCCESS");
        verify(repository).insertRefreshToken(eq(TENANT), eq(USER), anyString(), any());
    }

    @Test
    void requireTenantSemContextoFalha() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                Rules.tenant())).isInstanceOf(ResponseStatusException.class);
    }
}
