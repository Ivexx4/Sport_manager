package pt.voleibol.gestao.shared;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import pt.voleibol.gestao.tenancy.TenantContext;

/**
 * Regras repetidas pelos serviços: clube do pedido, papéis do ator e normalização de valores
 * recebidos do cliente. As fábricas de exceção evitam repetir o estado HTTP em cada validação.
 */
public final class Rules {

    /** Papéis que gerem o clube. */
    public static final Set<String> MANAGERS = Set.of("ADMIN", "DIRECAO");

    /** Papéis com acesso à gestão desportiva, além dos gestores do clube. */
    public static final Set<String> SPORT = Set.of("TREINADOR", "ATLETA", "ENCARREGADO");

    private Rules() {
    }

    public static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    public static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    public static ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }

    public static ResponseStatusException forbidden() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    /** Clube do pedido; um pedido sem clube é um erro do cliente. */
    public static UUID tenant() {
        return TenantContext.current().orElseThrow(() ->
                badRequest("Pedido sem clube: usa o subdomínio do clube"));
    }

    public static boolean isManager(Set<String> roles) {
        return roles.stream().anyMatch(MANAGERS::contains);
    }

    /** Gestor do clube ou papel desportivo: quem pode ver a operação desportiva. */
    public static boolean isSport(Set<String> roles) {
        return isManager(roles) || roles.stream().anyMatch(SPORT::contains);
    }

    /** Exige que o ator tenha pelo menos um dos papéis indicados. */
    public static void requireAny(Set<String> roles, Set<String> allowed) {
        if (roles.stream().noneMatch(allowed::contains)) throw forbidden();
    }

    public static void requireManager(Set<String> roles) {
        if (!isManager(roles)) throw forbidden();
    }

    public static void requireMember(Set<String> roles) {
        if (roles.isEmpty()) throw forbidden();
    }

    /** Quem gere o clube ou é treinador pode escrever. */
    public static void requireWritable(Set<String> roles) {
        if (!isManager(roles) && !roles.contains("TREINADOR")) throw forbidden();
    }

    /** Normaliza o estado e recusa valores fora do conjunto permitido. */
    public static String status(String status, Set<String> allowed) {
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw badRequest("Estado inválido");
        return normalized;
    }

    /** Texto obrigatório (nome), já sem espaços nas pontas. */
    public static String requiredText(String value) {
        if (value == null || value.isBlank()) throw badRequest("Nome obrigatório");
        return value.trim();
    }
}
