package ao.kixima.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Espelha tests/cors.test.js — a allow-list de CORS é partilhada entre o
 * REST e o tempo real; este teste cobre a lógica em si, isolada dos dois
 * consumidores, para nunca mais divergirem sem que um teste avise. O Node
 * corre com NODE_ENV=test (qualquer origem aceite); aqui constrói-se a
 * classe com o mesmo modo ("desenvolvimento ou teste") ligado.
 */
class CorsOriginsTest {

    private static final String APP_URL = "https://app.kixima.co.ao";

    private static CorsOrigins emTeste(String corsOrigins) {
        return new CorsOrigins(APP_URL, corsOrigins, true);
    }

    @Test
    void incluiSempreAsOrigensFixasDoCapacitorAndroidIos() {
        assertThat(emTeste("").allowList()).contains("https://localhost", "capacitor://localhost");
    }

    @Test
    void incluiOAppUrlConfigurado() {
        assertThat(emTeste("").allowList()).contains(APP_URL);
    }

    @Test
    void corsOriginsExtraEntraNaLista() {
        CorsOrigins cors = emTeste("https://parceiro.example.com, https://outro.example.com");
        assertThat(cors.allowList()).contains("https://parceiro.example.com", "https://outro.example.com");
    }

    @Test
    void semOriginCurlSameOriginOPedidoESempreAceite() {
        assertThat(emTeste("").origin(null)).isTrue();
        // Mesmo fora de desenvolvimento/teste.
        assertThat(new CorsOrigins(APP_URL, "", false).origin(null)).isTrue();
    }

    @Test
    void emTesteDesenvolvimentoQualquerOrigemEAceite() {
        assertThat(emTeste("").origin("https://qualquer-coisa.example.com")).isTrue();
    }

    @Test
    void foraDeDesenvolvimentoSoAAllowListEAutorizada() {
        // Não é um erro (não quebra pedidos same-origin que enviam Origin em POST); apenas não autoriza.
        CorsOrigins producao = new CorsOrigins(APP_URL, "https://parceiro.example.com", false);
        assertThat(producao.origin("https://qualquer-coisa.example.com")).isFalse();
        assertThat(producao.origin(APP_URL)).isTrue();
        assertThat(producao.origin("capacitor://localhost")).isTrue();
        assertThat(producao.origin("https://parceiro.example.com")).isTrue();
    }

    // --- Achado da auditoria: o construtor @Autowired NÃO pode tratar "nenhum
    // perfil activo" como desenvolvimento — ver CorsOriginsTest vs. EnvCorsProducaoTest. ---

    private static CorsOrigins comPerfis(String... perfis) {
        MockEnvironment env = new MockEnvironment();
        if (perfis.length > 0) env.setActiveProfiles(perfis);
        return new CorsOrigins(APP_URL, "", env);
    }

    @Test
    void semNenhumPerfilActivoNaoEQualquerOrigemAceite() {
        // Antes da correcção: SPRING_PROFILES_ACTIVE por definir caía no perfil
        // "default" do Spring, e "default" estava na lista de perfis permissivos.
        CorsOrigins semPerfil = comPerfis();
        assertThat(semPerfil.origin("https://atacante.example.com")).isFalse();
        assertThat(semPerfil.origin(APP_URL)).isTrue();
    }

    @Test
    void comPerfilProdQualquerOrigemContinuaRecusada() {
        assertThat(comPerfis("prod").origin("https://atacante.example.com")).isFalse();
    }

    @Test
    void comPerfilDevOuTestExplicitoQualquerOrigemEAceite() {
        assertThat(comPerfis("dev").origin("https://qualquer-coisa.example.com")).isTrue();
        assertThat(comPerfis("test").origin("https://qualquer-coisa.example.com")).isTrue();
    }
}
