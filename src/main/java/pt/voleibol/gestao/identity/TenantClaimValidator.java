package pt.voleibol.gestao.identity;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import pt.voleibol.gestao.tenancy.TenantContext;

/**
 * Confirma que o token pertence ao clube do pedido: o claim tenant_id tem de ser igual ao tenant
 * resolvido pelo subdomínio. Um token do clube A, usado no subdomínio do clube B, é recusado.
 */
class TenantClaimValidator implements OAuth2TokenValidator<Jwt> {

    static final String CLAIM = "tenant_id";

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String claim = jwt.getClaimAsString(CLAIM);
        Optional<UUID> current = TenantContext.current();
        if (claim != null && current.isPresent() && current.get().toString().equals(claim)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "O token não pertence a este clube", null));
    }
}
