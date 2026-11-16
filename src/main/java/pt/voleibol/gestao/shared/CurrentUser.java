package pt.voleibol.gestao.shared;

import java.util.Set;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Utilizador autenticado num pedido: identificador e papéis no clube, lidos do JWT emitido pelo
 * próprio serviço. É o único ponto onde o token é interpretado, para que controllers e serviços não
 * voltem a ler claims à mão.
 */
public record CurrentUser(UUID id, Set<Role> roles) {

    public CurrentUser {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public static CurrentUser of(Jwt jwt) {
        return new CurrentUser(UUID.fromString(jwt.getSubject()), Role.fromAll(jwt.getClaimAsStringList("roles")));
    }

    public boolean has(Role role) {
        return roles.contains(role);
    }

    public boolean hasAny(Set<Role> allowed) {
        return roles.stream().anyMatch(allowed::contains);
    }

    /** Gerem o clube: ADMIN e DIRECAO. */
    public boolean isManager() {
        return hasAny(Role.MANAGERS);
    }

    /** Gestores ou papéis desportivos: quem pode ver a operação desportiva. */
    public boolean isSport() {
        return isManager() || hasAny(Role.SPORT);
    }

    /** Gestores ou treinador: quem pode escrever na operação desportiva. */
    public boolean canWriteSport() {
        return isManager() || has(Role.TREINADOR);
    }

    /** Quem gere o financeiro do clube. */
    public boolean canManageBilling() {
        return hasAny(Role.BILLING);
    }

    public boolean isMember() {
        return !roles.isEmpty();
    }

    /** Códigos persistidos dos papéis, para consultas que comparam strings. */
    public Set<String> codes() {
        return Role.codes(roles);
    }
}
