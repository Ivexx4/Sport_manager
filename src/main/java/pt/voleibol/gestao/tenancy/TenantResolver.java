package pt.voleibol.gestao.tenancy;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Procura o tenant ativo a partir do slug. A tabela tenant não tem RLS (é da plataforma). */
@Component
class TenantResolver {

    private final JdbcClient jdbc;

    TenantResolver(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<UUID> findActiveTenantId(String slug) {
        return jdbc.sql("SELECT id FROM tenant WHERE slug = :slug AND status = 'ACTIVE'")
                .param("slug", slug)
                .query(UUID.class)
                .optional();
    }
}
