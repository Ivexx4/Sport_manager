package pt.voleibol.gestao.tenancy;

import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantFilterTest {

    private static final UUID CLUBE_A = UUID.fromString("00000000-0000-0000-0000-00000000aaaa");

    private final TenantResolver resolver = mock(TenantResolver.class);
    private final TenantFilter filter = new TenantFilter(resolver, "Clube.Test");

    @AfterEach
    void limpar() {
        TenantContext.clear();
    }

    @Test
    void resolveOTenantPeloSubdominio() throws Exception {
        when(resolver.findActiveTenantId("clube-a")).thenReturn(Optional.of(CLUBE_A));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("clube-a.clube.test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(TenantContext.current()).isEmpty(); // limpo no fim do pedido
    }

    @Test
    void hostMaiusculoEResolvidoSemDistinguirCaixa() throws Exception {
        when(resolver.findActiveTenantId("clube-a")).thenReturn(Optional.of(CLUBE_A));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("CLUBE-A.CLUBE.TEST");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void hostNuloNaoTemSubdominio() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getServerName()).thenReturn(null);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(resolver, never()).findActiveTenantId(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void hostSemOSufixoNaoTemSubdominio() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("outro-dominio.pt");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        verify(resolver, never()).findActiveTenantId(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void hostIgualAoSufixoNaoTemSubdominio() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName(".clube.test");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        verify(resolver, never()).findActiveTenantId(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void subdominioComSlugInvalidoDevolve404() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("-invalido.clube.test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(resolver, never()).findActiveTenantId(org.mockito.ArgumentMatchers.anyString());
        assertThat(response.getStatus()).isEqualTo(404);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void tenantDesconhecidoDevolve404() throws Exception {
        when(resolver.findActiveTenantId("desconhecido")).thenReturn(Optional.empty());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("desconhecido.clube.test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        verify(chain, never()).doFilter(request, response);
    }
}
