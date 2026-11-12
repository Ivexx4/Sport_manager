package pt.voleibol.gestao.identity;

import java.nio.charset.StandardCharsets;

/**
 * Regras mínimas para passwords novas: comprimento em vez de regras de composição (recomendação do NIST).
 * O limite de 72 bytes vem do bcrypt, que ignora (ou recusa) o que passa disso.
 */
final class PasswordPolicy {

    static final int MIN_LENGTH = 10;
    static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    /** Devolve a mensagem do problema, ou null se a password for aceitável. */
    static String check(String password, String email) {
        if (password == null || password.isBlank()) {
            return "A password é obrigatória";
        }
        if (password.codePointCount(0, password.length()) < MIN_LENGTH) {
            return "A password tem de ter pelo menos " + MIN_LENGTH + " caracteres";
        }
        if (!fitsBcrypt(password)) {
            return "A password não pode ter mais de " + MAX_BYTES + " bytes";
        }
        if (email != null && password.equalsIgnoreCase(email)) {
            return "A password não pode ser igual ao email";
        }
        return null;
    }

    static boolean fitsBcrypt(String password) {
        return password != null && password.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }
}
