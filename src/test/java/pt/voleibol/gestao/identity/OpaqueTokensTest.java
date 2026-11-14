package pt.voleibol.gestao.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpaqueTokensTest {

    @Test
    void geraTokensOpacosDiferentesCom256Bits() {
        String primeiro = OpaqueTokens.newToken();
        String segundo = OpaqueTokens.newToken();

        assertThat(primeiro).isNotEqualTo(segundo);
        assertThat(primeiro).hasSize(43); // 32 bytes em Base64 URL sem padding
    }

    @Test
    void hashCorrespondeAoSha256EmHexadecimal() throws Exception {
        String raw = "token-de-teste";

        String esperado = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));

        assertThat(OpaqueTokens.hash(raw)).isEqualTo(esperado).hasSize(64);
    }
}
