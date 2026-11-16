package pt.voleibol.gestao.identity;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.OptionalLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Códigos de uso único baseados no tempo (TOTP, RFC 6238), compatíveis com as aplicações
 * autenticadoras habituais: 6 dígitos, passo de 30 segundos e HMAC-SHA1 (o perfil por omissão do
 * RFC, e o único que algumas aplicações implementam).
 *
 * <p>A verificação aceita passos vizinhos ({@code skew}) por causa do desvio dos relógios e devolve o
 * passo aceite, para que o chamador o possa registar e recusar a repetição do mesmo código.
 */
final class Totp {

    static final int DIGITS = 6;
    static final int PERIOD_SECONDS = 30;

    /** 160 bits, o tamanho recomendado pelo RFC 4226 para HMAC-SHA1. */
    private static final int SECRET_BYTES = 20;
    private static final int MODULUS = 1_000_000;
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    /** Segredo novo em Base32 (sem padding), no formato que as aplicações autenticadoras esperam. */
    static String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return base32(bytes);
    }

    /**
     * Verifica o código introduzido. Devolve o passo aceite (o mais recente de todos os que
     * coincidem) ou vazio se o código não servir. O código tem de ter exatamente 6 dígitos.
     */
    static OptionalLong verify(String secret, String code, long epochSecond, int skew) {
        String candidate = normalise(code);
        if (candidate == null) return OptionalLong.empty();

        byte[] key;
        try {
            key = decodeBase32(secret);
        } catch (IllegalArgumentException e) {
            return OptionalLong.empty();
        }

        long step = epochSecond / PERIOD_SECONDS;
        int tolerance = Math.max(0, skew);
        // Do passo mais recente para o mais antigo: se dois passos derem o mesmo código, vale o mais alto.
        for (int offset = tolerance; offset >= -tolerance; offset--) {
            long current = step + offset;
            if (current < 0) continue;
            if (MessageDigest.isEqual(code(key, current).getBytes(StandardCharsets.US_ASCII),
                    candidate.getBytes(StandardCharsets.US_ASCII))) {
                return OptionalLong.of(current);
            }
        }
        return OptionalLong.empty();
    }

    /** Código do passo indicado; usado pela verificação e pelos testes com os vetores do RFC. */
    static String code(String secret, long step) {
        return code(decodeBase32(secret), step);
    }

    private static String code(byte[] key, long step) {
        byte[] counter = new byte[8];
        for (int i = 7; i >= 0; i--) {
            counter[i] = (byte) (step & 0xFF);
            step >>>= 8;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] digest = mac.doFinal(counter);
            // Truncagem dinâmica (RFC 4226, secção 5.3): os 4 bits finais escolhem o offset.
            int offset = digest[digest.length - 1] & 0x0F;
            int binary = ((digest[offset] & 0x7F) << 24)
                    | ((digest[offset + 1] & 0xFF) << 16)
                    | ((digest[offset + 2] & 0xFF) << 8)
                    | (digest[offset + 3] & 0xFF);
            return String.format(Locale.ROOT, "%0" + DIGITS + "d", binary % MODULUS);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HMAC-SHA1 é obrigatório em qualquer JRE; se faltar, é um erro de instalação.
            throw new IllegalStateException("HMAC-SHA1 indisponível", e);
        }
    }

    /** Aceita o código com espaços ou separadores, desde que sobrem exatamente 6 dígitos. */
    private static String normalise(String code) {
        if (code == null) return null;
        StringBuilder digits = new StringBuilder(DIGITS);
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c >= '0' && c <= '9') digits.append(c);
            else if (c != ' ' && c != '-') return null;
        }
        return digits.length() == DIGITS ? digits.toString() : null;
    }

    private static String base32(byte[] bytes) {
        StringBuilder out = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET[(buffer >> (bits - 5)) & 0x1F]);
                bits -= 5;
            }
            buffer &= (1 << bits) - 1;
        }
        if (bits > 0) out.append(ALPHABET[(buffer << (5 - bits)) & 0x1F]);
        return out.toString();
    }

    /** Base32 do RFC 4648, tolerante a espaços, hífenes e padding ('='). */
    static byte[] decodeBase32(String secret) {
        if (secret == null) throw new IllegalArgumentException("Segredo em falta");
        String value = secret.toUpperCase(Locale.ROOT).replace(" ", "").replace("-", "").replace("=", "");
        if (value.isEmpty()) throw new IllegalArgumentException("Segredo vazio");

        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < value.length(); i++) {
            int index = index(value.charAt(i));
            if (index < 0) throw new IllegalArgumentException("Segredo Base32 inválido");
            buffer = (buffer << 5) | index;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
                buffer &= (1 << bits) - 1;
            }
        }
        return out.toByteArray();
    }

    private static int index(char c) {
        for (int i = 0; i < ALPHABET.length; i++) {
            if (ALPHABET[i] == c) return i;
        }
        return -1;
    }
}
