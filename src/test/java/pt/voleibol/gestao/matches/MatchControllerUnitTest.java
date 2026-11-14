package pt.voleibol.gestao.matches;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class MatchControllerUnitTest {

    @Test
    void setScoreByPathUsaValoresDoPathQuandoRequestNulo() {
        MatchService service = org.mockito.Mockito.mock(MatchService.class);
        MatchController controller = new MatchController(service);
        UUID actor = UUID.randomUUID();
        UUID match = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject(actor.toString()).build();

        controller.setSetScoreByPath(jwt, match, 3, null);

        verify(service).setSetScore(actor, java.util.Set.of(), match, 3, 0, 0);
    }

    @Test
    void setScoreByPathPrefereCamposPreenchidosEPermiteRoles() {
        MatchService service = org.mockito.Mockito.mock(MatchService.class);
        MatchController controller = new MatchController(service);
        UUID actor = UUID.randomUUID();
        UUID match = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject(actor.toString())
                .claim("roles", java.util.List.of("MANAGER")).build();

        controller.setSetScoreByPath(jwt, match, 3,
                new MatchController.SetScoreRequest(null, 12, null));
        controller.setSetScoreByPath(jwt, match, 3,
                new MatchController.SetScoreRequest(2, 10, 8));

        verify(service).setSetScore(actor, java.util.Set.of("MANAGER"), match, 3, 12, 0);
        verify(service).setSetScore(actor, java.util.Set.of("MANAGER"), match, 2, 10, 8);
        verify(service, times(2)).setSetScore(
                org.mockito.ArgumentMatchers.eq(actor),
                org.mockito.ArgumentMatchers.eq(java.util.Set.of("MANAGER")),
                org.mockito.ArgumentMatchers.eq(match),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }
}
