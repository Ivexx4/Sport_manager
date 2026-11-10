package pt.clubevolei.tenancy;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Guarda o tenant do pedido corrente. Com ThreadLocal, o valor tem de ser sempre limpo
 * no fim (ver {@link TenantFilter}); com virtual threads cada pedido tem a sua própria thread, por isso é seguro.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static UUID require() {
        return current().orElseThrow(() -> new IllegalStateException("Sem tenant no contexto"));
    }

    public static void set(UUID tenantId) {
        CURRENT.set(tenantId);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Executa código como um tenant e restaura o estado anterior (útil em testes e jobs agendados). */
    public static <T> T callAs(UUID tenantId, Callable<T> action) {
        UUID previous = CURRENT.get();
        CURRENT.set(tenantId);
        try {
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void runAs(UUID tenantId, Runnable action) {
        callAs(tenantId, () -> {
            action.run();
            return null;
        });
    }
}
