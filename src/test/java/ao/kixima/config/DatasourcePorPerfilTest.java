package ao.kixima.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.PropertyPlaceholderHelper;

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A ligação à base de dados por perfil, sem Spring nem base de dados: lê os YAML tal como estão no JAR e resolve os
 * {@code ${VAR:omissão}} com um «ambiente» simulado.
 *
 * <p>Produção: serviço {@code db_kixima}, base {@code kixima}, utilizador {@code kixima} — URL e utilizador com valor por
 * omissão e substituíveis por variável; a SENHA só do ambiente. Local ({@code dev}/{@code test}): {@code localhost},
 * sempre separado da produção.
 */
class DatasourcePorPerfilTest {

    private static final String URL_PRODUCAO = "jdbc:postgresql://db_kixima:5432/kixima";
    private static final PropertyPlaceholderHelper HELPER = new PropertyPlaceholderHelper("${", "}", ":", false);

    private static Properties ler(String ficheiro) {
        YamlPropertiesFactoryBean f = new YamlPropertiesFactoryBean();
        f.setResources(new ClassPathResource(ficheiro));
        return f.getObject();
    }

    /** Como o Spring resolve o valor com estas variáveis de ambiente; falha se algum placeholder não tiver valor. */
    private static String resolver(Properties yaml, String chave, Map<String, String> ambiente) {
        String bruto = yaml.getProperty(chave);
        assertThat(bruto).as(chave).isNotNull();
        return HELPER.replacePlaceholders(bruto, ambiente::get);
    }

    @Test
    void producaoLigaAoServicoDbKiximaPorOmissao() {
        Properties prod = ler("application-prod.yml");
        assertThat(resolver(prod, "spring.datasource.url", Map.of("DATABASE_PASSWORD", "x"))).isEqualTo(URL_PRODUCAO);
        assertThat(resolver(prod, "spring.datasource.username", Map.of("DATABASE_PASSWORD", "x"))).isEqualTo("kixima");
    }

    @Test
    void urlEUtilizadorSaoSubstituiveisPorVariavelDeAmbiente() {
        Properties prod = ler("application-prod.yml");
        Map<String, String> env = Map.of("DATABASE_URL_JDBC", "jdbc:postgresql://outro-host:5432/outra_base",
                "DATABASE_USER", "outro_utilizador", "DATABASE_PASSWORD", "x");
        assertThat(resolver(prod, "spring.datasource.url", env)).isEqualTo("jdbc:postgresql://outro-host:5432/outra_base");
        assertThat(resolver(prod, "spring.datasource.username", env)).isEqualTo("outro_utilizador");
    }

    @Test
    void asenhaSoVemDoAmbienteESemEleFalhaOArranque() {
        Properties prod = ler("application-prod.yml");
        assertThat(prod.getProperty("spring.datasource.password")).isEqualTo("${DATABASE_PASSWORD}"); // sem «:omissão»
        assertThat(resolver(prod, "spring.datasource.password", Map.of("DATABASE_PASSWORD", "valor-do-ambiente"))).isEqualTo("valor-do-ambiente");
        assertThatThrownBy(() -> resolver(prod, "spring.datasource.password", Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DATABASE_PASSWORD");
    }

    @Test
    void localFicaSeparadoDaProducao() {
        for (String perfil : new String[]{"application-dev.yml", "application-test.yml"}) {
            String url = ler(perfil).getProperty("spring.datasource.url");
            assertThat(url).as(perfil).startsWith("jdbc:postgresql://localhost:").doesNotContain("db_kixima");
        }
        assertThat(ler("application-prod.yml").getProperty("spring.datasource.url")).doesNotContain("localhost");
    }

    @Test
    void hibernateSoValidaEOFlywayMantemOBaselineDasMigracoesExistentes() {
        Properties base = ler("application.yml");
        assertThat(base.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(base.getProperty("spring.flyway.baseline-on-migrate")).isEqualTo("true");
        assertThat(base.getProperty("spring.flyway.baseline-version")).isEqualTo("1");
        // Em produção o Flyway vem ligado por omissão (aplica a V2 a uma base que só tem a V1) e pode desligar-se.
        Properties prod = ler("application-prod.yml");
        assertThat(resolver(prod, "spring.flyway.enabled", Map.of())).isEqualTo("true");
        assertThat(resolver(prod, "spring.flyway.enabled", Map.of("FLYWAY_ENABLED", "false"))).isEqualTo("false");
    }
}
