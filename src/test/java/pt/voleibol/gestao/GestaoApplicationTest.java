package pt.voleibol.gestao;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * Cobre o arranque sem iniciar um contexto real: {@code SpringApplication.run} é substituído.
 * Sem isto, {@code main} lançaria sempre antes de chegar ao fim e a cobertura da linha ficaria em falta.
 */
class GestaoApplicationTest {

    @Test
    void mainDelegaOArranqueNoSpringApplication() {
        ConfigurableApplicationContext contexto = mock(ConfigurableApplicationContext.class);
        String[] args = {"--spring.main.web-application-type=none"};

        try (MockedStatic<SpringApplication> spring = mockStatic(SpringApplication.class)) {
            spring.when(() -> SpringApplication.run(GestaoApplication.class, args)).thenReturn(contexto);

            assertThatNoException().isThrownBy(() -> GestaoApplication.main(args));

            spring.verify(() -> SpringApplication.run(GestaoApplication.class, args));
        }
    }
}
