package pt.voleibol.gestao.shared;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Relógio da aplicação, na zona do clube. Datas e períodos derivados de "agora" passam pelo
 * {@link Clock} injetado, para os testes poderem fixar o tempo.
 */
@Configuration
class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.system(ZoneId.of("Europe/Lisbon"));
    }
}
