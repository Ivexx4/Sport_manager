package pt.voleibol.gestao.identity;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverExtraTest {

    @Test
    void semCabecalhoEncaminhadoUsaOLigacao() {
        ClientIpResolver resolver = resolver(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.1");
    }

    @Test
    void enderecoNuloDevolveValorNeutro() {
        ClientIpResolver resolver = resolver(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(null);

        assertThat(resolver.resolve(request)).isEqualTo("unknown");
    }

    @Test
    void primeiroValorNaoVazioDoCabecalhoEUsado() {
        ClientIpResolver resolver = resolver(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", ", 203.0.113.7");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    private static ClientIpResolver resolver(boolean trustForwardedHeaders) {
        return new ClientIpResolver(new LoginRateLimitProperties(
                true, 5, Duration.ofMinutes(5), Duration.ofMinutes(15),
                trustForwardedHeaders, 100, Duration.ofMinutes(10)));
    }
}
