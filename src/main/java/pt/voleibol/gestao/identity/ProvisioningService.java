package pt.voleibol.gestao.identity;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Provisionamento de novos clubes e do primeiro administrador (protegido por chave de bootstrap). */
@Service
public class ProvisioningService {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");

    record ProvisionResult(UUID tenantId, String slug, String name, UUID adminUserId, String adminEmail) {
    }

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final String bootstrapKey;

    public ProvisioningService(JdbcClient jdbc, PasswordEncoder passwordEncoder,
                               @Value("${app.platform.bootstrap-key:}") String bootstrapKey) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.bootstrapKey = bootstrapKey == null ? "" : bootstrapKey.trim();
    }

    @Transactional
    public ProvisionResult provisionTenant(String providedKey, String name, String slug,
                                           String adminEmail, String adminName, String adminPassword) {
        if (bootstrapKey.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Provisionamento desativado");
        }
        if (providedKey == null || providedKey.isBlank() || !bootstrapKey.equals(providedKey)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chave de bootstrap inválida");
        }

        String normalizedSlug = slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT);
        if (!SLUG.matcher(normalizedSlug).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Slug inválido");
        }
        String clubName = name == null ? "" : name.trim();
        if (clubName.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome do clube é obrigatório");
        }
        String email = adminEmail == null ? "" : adminEmail.trim().toLowerCase(Locale.ROOT);
        String aName = adminName == null ? "" : adminName.trim();
        if (email.isEmpty() || aName.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dados do administrador incompletos");
        }
        String passwordProblem = PasswordPolicy.check(adminPassword, email);
        if (passwordProblem != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, passwordProblem);
        }

        if (tenantSlugExists(normalizedSlug)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe um clube com este slug");
        }

        record Ids(UUID tenantId, UUID adminUserId) {}
        Ids ids = jdbc.sql("""
                        SELECT tenant_id, admin_user_id
                        FROM provision_tenant(:slug, :name, :email, :adminName, :hash)
                        """)
                .param("slug", normalizedSlug)
                .param("name", clubName)
                .param("email", email)
                .param("adminName", aName)
                .param("hash", passwordEncoder.encode(adminPassword))
                .query((rs, i) -> new Ids(rs.getObject("tenant_id", UUID.class), rs.getObject("admin_user_id", UUID.class)))
                .single();

        return new ProvisionResult(ids.tenantId(), normalizedSlug, clubName, ids.adminUserId(), email);
    }

    private boolean tenantSlugExists(String slug) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM tenant WHERE slug = :slug)")
                .param("slug", slug)
                .query(Boolean.class)
                .single();
    }
}
