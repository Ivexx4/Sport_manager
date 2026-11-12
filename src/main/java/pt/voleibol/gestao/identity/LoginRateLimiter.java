package pt.voleibol.gestao.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Limitação de tentativas de login por clube + IP, para travar ataques de força bruta.
 *
 * <p>Só as falhas contam. Ao atingir {@code max-attempts} falhas dentro de {@code window}, a chave
 * fica bloqueada durante {@code block-duration}; um login com sucesso limpa o contador. O bloqueio
 * devolve {@code 429} com {@code Retry-After}, sem chegar a consultar a base de dados.
 *
 * <p>O estado é local ao processo: com várias instâncias, cada uma tem o seu contador. Para um limite
 * global, substituir o mapa por um armazenamento partilhado (por exemplo, Redis). O número de chaves
 * é limitado por {@code max-entries} para o mapa não crescer sem controlo com emails/IPs aleatórios.
 */
@Component
class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    record Key(UUID tenantId, String clientIp) {
    }

    private record Entry(int failures, Instant windowStart, Instant blockedUntil) {
    }

    private final LoginRateLimitProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<Key, Entry> attempts = new ConcurrentHashMap<>();

    @Autowired
    LoginRateLimiter(LoginRateLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** Construtor usado nos testes, para controlar o tempo sem esperar. */
    LoginRateLimiter(LoginRateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Duração até o bloqueio terminar, ou vazio se a chave pode tentar autenticar-se. */
    Optional<Duration> retryAfter(UUID tenantId, String clientIp) {
        if (!properties.enabled() || tenantId == null) {
            return Optional.empty();
        }
        Entry entry = attempts.get(new Key(tenantId, clientIp));
        if (entry == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (!isBlocked(entry, now)) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(now, entry.blockedUntil()));
    }

    void recordFailure(UUID tenantId, String clientIp) {
        if (!properties.enabled() || tenantId == null) {
            return;
        }
        Key key = new Key(tenantId, clientIp);
        if (!ensureCapacity(key)) {
            return;
        }
        Instant now = clock.instant();
        AtomicBoolean blockedNow = new AtomicBoolean(false);

        attempts.compute(key, (k, current) -> {
            if (current != null && isBlocked(current, now)) {
                // Já bloqueado: as tentativas seguintes são recusadas antes de chegar aqui.
                return current;
            }
            // Uma janela nova começa quando não há registo, quando a janela anterior expirou
            // ou quando um bloqueio anterior terminou (dá ao cliente um novo conjunto de tentativas).
            boolean newWindow = current == null
                    || current.blockedUntil() != null
                    || !now.isBefore(current.windowStart().plus(properties.window()));
            int failures = newWindow ? 1 : current.failures() + 1;
            Instant windowStart = newWindow ? now : current.windowStart();
            Instant blockedUntil = failures >= properties.maxAttempts()
                    ? now.plus(properties.blockDuration())
                    : null;
            if (blockedUntil != null) {
                blockedNow.set(true);
            }
            return new Entry(failures, windowStart, blockedUntil);
        });

        if (blockedNow.get()) {
            log.warn("Login bloqueado por excesso de falhas: tenant={} ip={} durante {}s",
                    tenantId, clientIp, properties.blockDuration().toSeconds());
        }
    }

    void recordSuccess(UUID tenantId, String clientIp) {
        if (!properties.enabled() || tenantId == null) {
            return;
        }
        attempts.remove(new Key(tenantId, clientIp));
    }

    /** Remove entradas de janelas e bloqueios já terminados. */
    @Scheduled(fixedDelayString = "${app.security.login-rate-limit.cleanup-interval:PT10M}")
    void purgeExpired() {
        if (!properties.enabled()) {
            return;
        }
        Instant now = clock.instant();
        attempts.entrySet().removeIf(entry -> isExpired(entry.getValue(), now));
    }

    private boolean ensureCapacity(Key key) {
        if (attempts.size() < properties.maxEntries() || attempts.containsKey(key)) {
            return true;
        }
        purgeExpired();
        if (attempts.size() < properties.maxEntries() || attempts.containsKey(key)) {
            return true;
        }
        // Não deixa o mapa crescer sem controlo; as chaves já registadas continuam a ser contabilizadas.
        log.debug("Limitador de login cheio ({} chaves): tentativa não contabilizada", attempts.size());
        return false;
    }

    private static boolean isBlocked(Entry entry, Instant now) {
        return entry.blockedUntil() != null && now.isBefore(entry.blockedUntil());
    }

    private boolean isExpired(Entry entry, Instant now) {
        if (isBlocked(entry, now)) {
            return false;
        }
        return !now.isBefore(entry.windowStart().plus(properties.window()));
    }
}
