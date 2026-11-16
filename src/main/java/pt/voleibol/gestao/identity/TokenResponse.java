package pt.voleibol.gestao.identity;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/** Resposta do login concluído, partilhada pelo login, pelo refresh e pelo segundo fator. */
record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn) {

    static TokenResponse from(AuthService.Tokens tokens) {
        return new TokenResponse(tokens.accessToken(), tokens.refreshToken(), "Bearer",
                tokens.expiresInSeconds());
    }

    static ResponseEntity<TokenResponse> ok(AuthService.Tokens tokens) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(from(tokens));
    }
}
