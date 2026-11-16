package pt.voleibol.gestao.shared;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Entrega aos controllers o {@link CurrentUser} do pedido, a partir do JWT que a cadeia de segurança
 * já validou. Os métodos passam a declarar {@code CurrentUser user} em vez de
 * {@code @AuthenticationPrincipal Jwt jwt} e de repetir {@code CurrentUser.from(jwt)}.
 */
class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return CurrentUser.class.equals(parameter.getParameterType());
    }

    @Override
    public CurrentUser resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                       NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return CurrentUser.from(jwt);
        }
        // Só acontece num método cujo caminho não passe pela autenticação: é um erro de configuração.
        throw new IllegalStateException("Parâmetro CurrentUser num pedido sem JWT autenticado");
    }
}
