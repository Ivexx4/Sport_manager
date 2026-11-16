package pt.voleibol.gestao.shared;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Papel de um utilizador num clube ({@code membership.role}), também usado nos claims do JWT.
 *
 * <p>O código persistido é o nome do enum: os valores não podem ser renomeados sem uma migração.
 */
public enum Role {

    ADMIN, DIRECAO, FINANCEIRO, TREINADOR, ATLETA, ENCARREGADO;

    /** Papéis que gerem o clube. */
    public static final Set<Role> MANAGERS = Set.copyOf(EnumSet.of(ADMIN, DIRECAO));

    /** Papéis com acesso à operação desportiva, além dos gestores do clube. */
    public static final Set<Role> SPORT = Set.copyOf(EnumSet.of(TREINADOR, ATLETA, ENCARREGADO));

    /** Papéis com acesso ao financeiro do clube. */
    public static final Set<Role> BILLING = Set.copyOf(EnumSet.of(ADMIN, DIRECAO, FINANCEIRO));

    /** Papel a partir do código persistido; códigos desconhecidos são ignorados em vez de falharem. */
    public static Optional<Role> from(String code) {
        if (code == null) return Optional.empty();
        try {
            return Optional.of(valueOf(code.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static Set<Role> fromAll(Collection<String> codes) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        if (codes != null) codes.forEach(code -> from(code).ifPresent(roles::add));
        return Set.copyOf(roles);
    }

    /** Códigos persistidos, para claims e consultas. */
    public static Set<String> codes(Collection<Role> roles) {
        return roles.stream().map(Role::name).collect(Collectors.toUnmodifiableSet());
    }
}
