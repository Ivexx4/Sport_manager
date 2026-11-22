package pt.voleibol.gestao.shared;

import java.util.Map;
import java.util.Optional;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import pt.voleibol.gestao.tenancy.TenantContext;

/** Verificação rápida de que a aplicação arranca e de que o tenant é resolvido pelo subdomínio. */
@RestController
class PingController {

    private final Optional<DevReadiness> readiness;

    PingController(Optional<DevReadiness> readiness) {
        this.readiness = readiness;
    }

    @GetMapping("/api/v1/ping")
    Map<String, Object> ping() {
        readiness.ifPresent(DevReadiness::requireReady);
        String tenant = TenantContext.current().map(Object::toString).orElse("nenhum");
        return readiness.isPresent()
                ? Map.of("status", "ok", "tenant", tenant, "database", "ready",
                        "processId", ProcessHandle.current().pid())
                : Map.of("status", "ok", "tenant", tenant);
    }
}
