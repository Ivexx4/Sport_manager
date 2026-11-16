package pt.voleibol.gestao.identity;

import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Vetores do RFC 6238 (perfil HMAC-SHA1), truncados a 6 dígitos como na aplicação.
 * O segredo dos vetores é o ASCII "12345678901234567890", em Base32.
 */
class TotpTest {

    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private static final long NOW = 1_700_000_000L;

    @Test
    void segredoDoRfcEmBase32CorrespondeAoAscii() {
        assertThat(Totp.decodeBase32(SECRET))
                .isEqualTo("12345678901234567890".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void codigosConferemComOsVetoresDoRfc6238() {
        assertThat(Totp.code(SECRET, 59 / 30)).isEqualTo("287082");
        assertThat(Totp.code(SECRET, 1_111_111_109L / 30)).isEqualTo("081804");
        assertThat(Totp.code(SECRET, 1_111_111_111L / 30)).isEqualTo("050471");
        assertThat(Totp.code(SECRET, 1_234_567_890L / 30)).isEqualTo("005924");
        assertThat(Totp.code(SECRET, 2_000_000_000L / 30)).isEqualTo("279037");
        assertThat(Totp.code(SECRET, 20_000_000_000L / 30)).isEqualTo("353130");
    }

    @Test
    void segredoNovoTem160BitsEmBase32() {
        String secret = Totp.newSecret();

        assertThat(secret).hasSize(32).doesNotContain("=");
        assertThat(Totp.decodeBase32(secret)).hasSize(20);
        assertThat(Totp.newSecret()).isNotEqualTo(secret);
    }

    @Test
    void verificacaoAceitaOPassoAtualEOsVizinhos() {
        long step = NOW / Totp.PERIOD_SECONDS;
        String code = Totp.code(SECRET, step);

        assertThat(Totp.verify(SECRET, code, NOW, 0)).hasValue(step);
        // O mesmo código continua a valer no passo seguinte, dentro da tolerância.
        assertThat(Totp.verify(SECRET, code, NOW + Totp.PERIOD_SECONDS, 1)).hasValue(step);
        assertThat(Totp.verify(SECRET, code, NOW + 2L * Totp.PERIOD_SECONDS, 1)).isEmpty();
        // Um código gerado para o futuro também é aceite com tolerância (relógio adiantado).
        assertThat(Totp.verify(SECRET, Totp.code(SECRET, step + 1), NOW, 1)).hasValue(step + 1);
    }

    @Test
    void codigoErradoOuMalFormatadoNaoPassa() {
        assertThat(Totp.verify(SECRET, "000000", NOW, 1)).isEmpty();
        assertThat(Totp.verify(SECRET, "12345", NOW, 1)).isEmpty();
        assertThat(Totp.verify(SECRET, "abcdef", NOW, 1)).isEmpty();
        assertThat(Totp.verify(SECRET, null, NOW, 1)).isEmpty();
        assertThat(Totp.verify("não-é-base32", "123456", NOW, 1)).isEmpty();
    }

    @Test
    void aceitaOCodigoComSeparadores() {
        long step = NOW / Totp.PERIOD_SECONDS;
        String code = Totp.code(SECRET, step);

        OptionalLong verificado = Totp.verify(SECRET, code.substring(0, 3) + " " + code.substring(3), NOW, 0);

        assertThat(verificado).hasValue(step);
    }

    @Test
    void base32InvalidoEDetetado() {
        assertThatThrownBy(() -> Totp.decodeBase32("ABC1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
