package pt.voleibol.gestao.identity;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityConfigTest {

    private final SecurityConfig config = new SecurityConfig();

    @Test
    void segredoValidoGeraChaveHmac() {
        byte[] bytes = "clave-de-teste-com-mais-de-32-bytes-ok!".getBytes(StandardCharsets.UTF_8);
        JwtProperties properties = properties(Base64.getEncoder().encodeToString(bytes));

        assertThat(config.jwtSecretKey(properties).getAlgorithm()).isEqualTo("HmacSHA256");
        assertThat(config.jwtSecretKey(properties).getEncoded()).isEqualTo(bytes);
    }

    @Test
    void segredoQueNaoEstaEmBase64Falha() {
        assertThatThrownBy(() -> config.jwtSecretKey(properties("não-é-base64!!")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base64");
    }

    @Test
    void segredoComMenosDe32BytesFalha() {
        String curto = Base64.getEncoder().encodeToString("curto".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> config.jwtSecretKey(properties(curto)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    private static JwtProperties properties(String secret) {
        return new JwtProperties(secret, "gestao-clubes-voleibol",
                Duration.ofMinutes(15), Duration.ofDays(30));
    }
}
