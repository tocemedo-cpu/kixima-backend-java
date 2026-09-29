package ao.kixima.user;

import ao.kixima.security.PersonaRole;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Espelha backend/src/utils/passwordPolicy.js — ponto único da política de
 * senhas. Aplica-se a quem DEFINE ou MUDA uma senha, nunca ao login (quem já
 * tem uma senha curta continua a entrar).
 */
@Component
public class PasswordPolicy {

    public static final Set<PersonaRole> PERFIS_SENSIVEIS = Set.of(
            PersonaRole.COMPANY_ADMIN, PersonaRole.FINANCEIRO, PersonaRole.ADMIN_SISTEMA);

    public static final int MINIMO = 10;
    public static final int MINIMO_SENSIVEL = 12;

    private static final Set<String> PROIBIDAS = Set.of(
            "12345678", "123456789", "1234567890", "123456789010", "12345678910",
            "password", "password1", "password123", "passw0rd", "senha123", "senha1234",
            "qwerty123", "qwertyuiop", "abc123456", "admin123", "administrador",
            "iloveyou", "welcome123", "letmein123", "sunshine1", "football1",
            "kixima", "kixima123", "kixima2026", "kixima@123", "kiximakixima",
            "angola123", "luanda123", "petroleo123");

    private static final Pattern UM_SO_CARACTER_REPETIDO = Pattern.compile("^(.)\\1+$");
    private static final String SEQUENCIA = "0123456789abcdefghijklmnopqrstuvwxyz";

    public int minimoPara(PersonaRole role) {
        return role != null && PERFIS_SENSIVEIS.contains(role) ? MINIMO_SENSIVEL : MINIMO;
    }

    private boolean ePobre(String senha) {
        String s = senha == null ? "" : senha;
        if (UM_SO_CARACTER_REPETIDO.matcher(s).matches()) return true;
        String min = s.toLowerCase();
        return SEQUENCIA.contains(min) || new StringBuilder(SEQUENCIA).reverse().toString().contains(min);
    }

    /**
     * Valida uma senha. Devolve null se serve, ou a razão pela qual não serve.
     *
     * @param senha a senha escolhida
     * @param role  perfil a que a conta se destina (pode ser null)
     * @param email email da conta, para recusar a senha igual ao próprio email (pode ser null)
     */
    public String validar(String senha, PersonaRole role, String email) {
        String s = senha == null ? "" : senha;
        int min = minimoPara(role);

        if (s.length() < min) {
            return role != null && PERFIS_SENSIVEIS.contains(role)
                    ? "Esta conta aprova operações com dinheiro, por isso a senha precisa de pelo menos " + min + " caracteres."
                    : "A senha deve ter pelo menos " + min + " caracteres.";
        }
        if (PROIBIDAS.contains(s.toLowerCase())) {
            return "Esta senha é das mais usadas no mundo e é testada em primeiro lugar num ataque. Escolha outra.";
        }
        if (ePobre(s)) {
            return "A senha não pode ser uma sequência nem um carácter repetido.";
        }
        if (email != null && !email.isBlank()) {
            String local = email.split("@")[0].toLowerCase();
            if (local.length() >= 4 && s.toLowerCase().contains(local)) {
                return "A senha não pode conter o seu email.";
            }
        }
        return null;
    }

    public String validar(String senha) {
        return validar(senha, null, null);
    }
}
