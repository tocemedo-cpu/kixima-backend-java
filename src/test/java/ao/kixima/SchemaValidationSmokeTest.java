package ao.kixima;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * M0 — verificação obrigatória do plano (secção 2): confirma que as
 * entidades JPA batem exactamente com o schema já existente
 * (`hibernate.ddl-auto: validate`, application-test.yml). Se este teste
 * falhar, uma entidade tem um @Table/@Column que não corresponde à tabela
 * real — falha alto e cedo, antes de qualquer domínio depender da entidade.
 */
@SpringBootTest
@ActiveProfiles("test")
class SchemaValidationSmokeTest {

    @Test
    void contextCarregaEValidaOSchemaContraABaseExistente() {
        // O simples arranque do contexto Spring já corre a validação do
        // Hibernate contra a base — se chegar aqui sem excepção, passou.
    }
}
