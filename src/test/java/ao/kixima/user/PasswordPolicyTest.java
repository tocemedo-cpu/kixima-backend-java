package ao.kixima.user;

import ao.kixima.security.PersonaRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Porte literal do describe("regras") de tests/password-policy.test.js —
 * mesmas senhas de teste, mesmas asserções.
 */
class PasswordPolicyTest {

    private final PasswordPolicy politica = new PasswordPolicy();

    @Test
    void oMinimoGeralE10Caracteres() {
        assertThat(politica.validar("Curta1")).contains("pelo menos 10");
        assertThat(politica.validar("Cabo-Umbilical")).isNull();
    }

    @Test
    void quemAprovaDinheiroPrecisaDe12() {
        String dez = "Valvula-42";
        assertThat(politica.validar(dez)).isNull();
        for (PersonaRole role : new PersonaRole[]{PersonaRole.COMPANY_ADMIN, PersonaRole.FINANCEIRO, PersonaRole.ADMIN_SISTEMA}) {
            assertThat(politica.validar(dez, role, null)).contains("pelo menos 12");
        }
        for (PersonaRole role : new PersonaRole[]{PersonaRole.COMPRADOR, PersonaRole.FORNECEDOR}) {
            assertThat(politica.validar(dez, role, null)).isNull();
        }
    }

    @Test
    void recusaAsSenhasMaisUsadasDoMundo() {
        for (String s : new String[]{"12345678", "password123", "kixima2026", "Kixima@123"}) {
            assertThat(politica.validar(s)).isNotNull();
        }
    }

    @Test
    void recusaSequenciasECaracteresRepetidos() {
        assertThat(politica.validar("abcdefghij")).contains("sequência");
        assertThat(politica.validar("aaaaaaaaaaaa")).containsAnyOf("sequência", "repetido");
        assertThat(politica.validar("1234567890")).isNotNull();
    }

    @Test
    void recusaASenhaQueContemOProprioEmail() {
        assertThat(politica.validar("joanasilva-2026", null, "joanasilva@empresa.co.ao")).contains("email");
    }
}
