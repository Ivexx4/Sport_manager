package pt.voleibol.gestao.matches;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import pt.voleibol.gestao.shared.CurrentUser;
import pt.voleibol.gestao.shared.Role;

class MatchControllerUnitTest {

    @Test
    void setScoreByPathUsaValoresDoPathQuandoRequestNulo() {
        MatchService service = org.mockito.Mockito.mock(MatchService.class);
        MatchController controller = new MatchController(service);
        UUID actor = UUID.randomUUID();
        UUID match = UUID.randomUUID();
        CurrentUser user = new CurrentUser(actor, Set.of());

        controller.setSetScoreByPath(user, match, 3, null);

        verify(service).setSetScore(new CurrentUser(actor, Set.of()), match, 3, 0, 0);
    }

    @Test
    void setScoreByPathPrefereCamposPreenchidosEPermiteRoles() {
        MatchService service = org.mockito.Mockito.mock(MatchService.class);
        MatchController controller = new MatchController(service);
        UUID actor = UUID.randomUUID();
        UUID match = UUID.randomUUID();
        CurrentUser admin = new CurrentUser(actor, Set.of(Role.ADMIN));

        controller.setSetScoreByPath(admin, match, 3,
                new MatchController.SetScoreRequest(null, 12, null));
        controller.setSetScoreByPath(admin, match, 3,
                new MatchController.SetScoreRequest(2, 10, 8));

        verify(service).setSetScore(admin, match, 3, 12, 0);
        verify(service).setSetScore(admin, match, 2, 10, 8);
        verify(service, times(2)).setSetScore(
                org.mockito.ArgumentMatchers.eq(admin),
                org.mockito.ArgumentMatchers.eq(match),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }
}
