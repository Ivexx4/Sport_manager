package pt.voleibol.gestao.shared;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import pt.voleibol.gestao.tenancy.TenantContext;

/** Verificação rápida de que a aplicação arranca e de que o tenant é resolvido pelo subdomínio. */
@RestController
class PingController {

    @GetMapping("/api/v1/ping")
    Map<String, Object> ping() {
        return Map.of(
                "status", "ok",
                "tenant", TenantContext.current().map(Object::toString).orElse("nenhum"));
    }
}
