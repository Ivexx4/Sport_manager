package pt.clubevolei.security;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Substituto do endpoint do Actuator na fatia MVC, que não inclui o Actuator.
 * Permite validar a regra de acesso público definida em {@link pt.clubevolei.security.SecurityConfig}.
 */
@RestController
class HealthStubController {

    @GetMapping("/actuator/health")
    Health health() {
        return new Health("UP");
    }

    record Health(String status) {
    }
}
