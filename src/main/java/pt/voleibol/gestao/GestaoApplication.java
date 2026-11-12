package pt.voleibol.gestao;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GestaoApplication {

    static void main(String[] args) {
        SpringApplication.run(GestaoApplication.class, args);
    }
}
