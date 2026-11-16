package pt.voleibol.gestao.shared;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Liga ao MVC os helpers transversais de {@code shared}. Por agora só o resolver do
 * {@link CurrentUser}, que os controllers declaram como parâmetro sem anotação.
 */
@Configuration
class WebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver());
    }
}
