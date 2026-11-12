package ao.kixima.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O Spring Security, sem um {@code UserDetailsService}, cria o utilizador em memória "user" e escreve a senha gerada no
 * log a cada arranque. A aplicação autentica só por JWT, por isso essa auto-configuração está excluída: nenhum
 * {@code UserDetailsService} existe e nenhuma credencial gerada chega ao log.
 */
@SpringBootTest
@ActiveProfiles("test")
class SemSenhaGeradaPeloSpringSecurityTest {

    @Autowired
    private ApplicationContext contexto;

    @Test
    void naoExisteNenhumUtilizadorEmMemoria() {
        assertThat(contexto.getBeanNamesForType(UserDetailsService.class)).isEmpty();
    }
}
