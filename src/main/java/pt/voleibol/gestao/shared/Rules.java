package pt.voleibol.gestao.shared;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import pt.voleibol.gestao.tenancy.TenantContext;

/**
 * Regras repetidas pelos serviços: clube do pedido, papéis do ator e normalização de valores
 * recebidos do cliente.
 */
public final class Rules {

    /** Papéis que gerem o clube. */
    public static final Set<String> MANAGERS = Set.of("ADMIN", "DIRECAO");

    private Rules() {
    }

    /** Clube do pedido; um pedido sem clube é um erro do cliente. */
    public static UUID tenant() {
        return TenantContext.current().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pedido sem clube: usa o subdomínio do clube"));
    }

    public static boolean isManager(Set<String> roles) {
        return roles.stream().anyMatch(MANAGERS::contains);
    }

    public static void requireManager(Set<String> roles) {
        if (!isManager(roles)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    public static void requireMember(Set<String> roles) {
        if (roles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    /** Quem gere o clube ou é treinador pode escrever. */
    public static void requireWritable(Set<String> roles) {
        if (!isManager(roles) && !roles.contains("TREINADOR")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    /** Normaliza o estado e recusa valores fora do conjunto permitido. */
    public static String status(String status, Set<String> allowed) {
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estado inválido");
        }
        return normalized;
    }

    /** Texto obrigatório (nome), já sem espaços nas pontas. */
    public static String requiredText(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome obrigatório");
        }
        return value.trim();
    }
}
