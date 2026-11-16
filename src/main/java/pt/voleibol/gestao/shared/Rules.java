package pt.voleibol.gestao.shared;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import pt.voleibol.gestao.tenancy.TenantContext;

/**
 * Regras repetidas pelos serviços: clube do pedido, papéis do ator, normalização de valores
 * recebidos do cliente e as fábricas de exceção que evitam repetir o estado HTTP em cada validação.
 */
public final class Rules {

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

    /** Exige que o ator tenha pelo menos um dos papéis indicados. */
    public static void requireAny(CurrentUser user, Set<Role> allowed) {
        if (!user.hasAny(allowed)) throw forbidden();
    }

    public static void requireManager(CurrentUser user) {
        if (!user.isManager()) throw forbidden();
    }

    public static void requireMember(CurrentUser user) {
        if (!user.isMember()) throw forbidden();
    }

    /** Quem gere o clube ou é treinador pode escrever. */
    public static void requireWritable(CurrentUser user) {
        if (!user.canWriteSport()) throw forbidden();
    }

    /** Texto obrigatório (nome), já sem espaços nas pontas. */
    public static String requiredText(String value) {
        if (value == null || value.isBlank()) throw badRequest("Nome obrigatório");
        return value.trim();
    }

    /**
     * Valor de estado recebido do cliente, normalizado para o enum do domínio. Qualquer valor fora do
     * enum é um erro do cliente, com o nome do campo na mensagem.
     */
    public static <E extends Enum<E>> E parse(Class<E> type, String raw, String field) {
        if (raw == null || raw.isBlank()) throw badRequest(field + " obrigatório");
        return find(type, raw).orElseThrow(() -> badRequest(field + " inválido"));
    }

    /** Como {@link #parse(Class, String, String)}, mas com o valor por omissão quando o pedido o omite. */
    public static <E extends Enum<E>> E parse(Class<E> type, String raw, String field, E fallback) {
        return raw == null || raw.isBlank() ? fallback : parse(type, raw, field);
    }

    /** Valor do enum ou vazio, para importações que ignoram linhas inválidas em vez de as rejeitar. */
    public static <E extends Enum<E>> Optional<E> find(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        try {
            return Optional.of(Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
