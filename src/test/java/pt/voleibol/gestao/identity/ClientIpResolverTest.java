package pt.voleibol.gestao.identity;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    @Test
    void porOmissaoUsaOEnderecoDaLigacao() {
        ClientIpResolver resolver = resolver(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.7");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.1");
    }

    @Test
    void atrasDeProxyUsaOPrimeiroValorDeXForwardedFor() {
        ClientIpResolver resolver = resolver(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void cabecalhoVazioRecorreAoEnderecoDaLigacao() {
        ClientIpResolver resolver = resolver(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", " , ");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.1");
    }

    @Test
    void semEnderecoDevolveValorNeutro() {
        ClientIpResolver resolver = resolver(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("");

        assertThat(resolver.resolve(request)).isEqualTo("unknown");
    }

    private static ClientIpResolver resolver(boolean trustForwardedHeaders) {
        return new ClientIpResolver(new LoginRateLimitProperties(
                true, 5, Duration.ofMinutes(5), Duration.ofMinutes(15),
                trustForwardedHeaders, 100, Duration.ofMinutes(10)));
    }
}
