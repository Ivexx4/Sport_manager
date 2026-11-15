package pt.voleibol.gestao.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTest {

    private static final String EMAIL = "atleta@example.pt";

    @Test
    void passwordNulaOuEmBrancoEObrigatoria() {
        assertThat(PasswordPolicy.check(null, EMAIL)).isEqualTo("A password é obrigatória");
        assertThat(PasswordPolicy.check("   ", EMAIL)).isEqualTo("A password é obrigatória");
        assertThat(PasswordPolicy.check("", null)).isEqualTo("A password é obrigatória");
    }

    @Test
    void passwordCurtaERecusada() {
        assertThat(PasswordPolicy.check("123456789", EMAIL))
                .isEqualTo("A password tem de ter pelo menos 10 caracteres");
    }

    @Test
    void passwordComMaisDeSetentaEDoisBytesERecusada() {
        assertThat(PasswordPolicy.check("a".repeat(73), EMAIL))
                .isEqualTo("A password não pode ter mais de 72 bytes");
        // Caracteres acentuados ocupam dois bytes em UTF-8.
        assertThat(PasswordPolicy.check("ç".repeat(37), EMAIL))
                .isEqualTo("A password não pode ter mais de 72 bytes");
    }

    @Test
    void passwordIgualAoEmailERecusadaSemDistinguirMaiusculas() {
        assertThat(PasswordPolicy.check("ATLETA@EXAMPLE.PT", EMAIL))
                .isEqualTo("A password não pode ser igual ao email");
        assertThat(PasswordPolicy.check("atleta@example.pt", null)).isNull();
    }

    @Test
    void passwordAceitavelDevolveNulo() {
        assertThat(PasswordPolicy.check("uma-password-segura", EMAIL)).isNull();
        assertThat(PasswordPolicy.check("abcdefghij", null)).isNull();
    }

    @Test
    void fitsBcryptAceitaAte72Bytes() {
        assertThat(PasswordPolicy.fitsBcrypt(null)).isFalse();
        assertThat(PasswordPolicy.fitsBcrypt("a".repeat(72))).isTrue();
        assertThat(PasswordPolicy.fitsBcrypt("a".repeat(73))).isFalse();
    }

    @Test
    void limiteDeBytesIgnoraOPontoDeCodigo() {
        assertThat(PasswordPolicy.MAX_BYTES).isEqualTo(72);
        assertThat(PasswordPolicy.MIN_LENGTH).isEqualTo(10);
    }
}
