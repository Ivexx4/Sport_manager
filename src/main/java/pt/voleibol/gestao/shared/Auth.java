package pt.voleibol.gestao.shared;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Utilizador autenticado, lido do JWT emitido pelo próprio serviço.
 */
public final class Auth {

    private Auth() {
    }

    public static UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    public static Set<String> roles(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles == null ? Set.of() : Set.copyOf(roles);
    }
}
