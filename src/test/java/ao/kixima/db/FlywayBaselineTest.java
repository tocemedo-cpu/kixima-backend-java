package ao.kixima.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * O arranque com {@code FLYWAY_ENABLED=true} tem de funcionar numa base VAZIA e numa base que JÁ TEM o schema.
 *
 * O que isto protege: o Hibernate corre com {@code ddl-auto: validate} e nunca cria tabelas, por isso uma base vazia
 * falha com {@code Schema-validation: missing table [addon_cobrancas]} (a primeira tabela que valida) se o Flyway não
 * a preencher. E, com {@code baseline-version: 0}, uma base já existente (sem histórico do Flyway, ex.: criada pelo
 * Prisma) fazia o Flyway REAPLICAR o V1 sobre tabelas que já existem e falhar com "already exists".
 *
 * Cada cenário corre numa base temporária própria (CREATE DATABASE) e apaga-a no fim; nunca toca na base de testes
 * partilhada nem em nenhuma outra. Sem permissão para criar bases, o teste é ignorado em vez de falhar.
 * A configuração do Flyway é lida do próprio application.yml, para o teste falhar se alguém a voltar a estragar.
 */
class FlywayBaselineTest {

    private static final String URL_ADMIN = env("DATABASE_URL_JDBC", "jdbc:postgresql://localhost:5432/kixima_test");
    private static final String USER = env("DATABASE_USER", "kixima");
    private static final String PASSWORD = env("DATABASE_PASSWORD", "kixima");

    private static String env(String nome, String omissao) {
        String valor = System.getenv(nome);
        return valor == null || valor.isBlank() ? omissao : valor;
    }

    /** `jdbc:postgresql://host:porta/base?opcoes` -> `jdbc:postgresql://host:porta/<outra>`. */
    private static String urlDa(String base) {
        String semOpcoes = URL_ADMIN.split("\\?")[0];
        return semOpcoes.substring(0, semOpcoes.lastIndexOf('/') + 1) + base;
    }

    private static Properties configDoYaml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        return yaml.getObject();
    }

    private static Flyway flywayComAConfigDaAplicacao(String base) {
        Properties cfg = configDoYaml();
        return Flyway.configure()
                .dataSource(urlDa(base), USER, PASSWORD)
                .locations("classpath:db/migration")
                .baselineOnMigrate(Boolean.parseBoolean(cfg.getProperty("spring.flyway.baseline-on-migrate")))
                .baselineVersion(cfg.getProperty("spring.flyway.baseline-version"))
                .load();
    }

    private static void comBaseTemporaria(Consumer<String> cenario) throws SQLException {
        String base = "kx_fw_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection admin = DriverManager.getConnection(URL_ADMIN, USER, PASSWORD); Statement st = admin.createStatement()) {
            try {
                st.execute("CREATE DATABASE " + base);
            } catch (SQLException semPermissao) {
                assumeTrue(false, "sem permissão para CREATE DATABASE — teste ignorado: " + semPermissao.getMessage());
            }
            try {
                cenario.accept(base);
            } finally {
                st.execute("DROP DATABASE IF EXISTS " + base + " WITH (FORCE)");
            }
        }
    }

    private static boolean existe(Connection c, String tabela) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT to_regclass('public." + tabela + "') IS NOT NULL")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    @Test
    void baseVaziaRecebeOSchemaCompleto() throws Exception {
        comBaseTemporaria(base -> {
            var resultado = flywayComAConfigDaAplicacao(base).migrate();
            assertThat(resultado.migrationsExecuted).isEqualTo(1);
            try (Connection c = DriverManager.getConnection(urlDa(base), USER, PASSWORD)) {
                // addon_cobrancas é a tabela que o Hibernate reporta em falta numa base vazia.
                assertThat(existe(c, "addon_cobrancas")).isTrue();
                assertThat(existe(c, "users")).isTrue();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void baseQueJaTemOSchemaNaoERepetidaEnemPerdeDados() throws Exception {
        comBaseTemporaria(base -> {
            try (Connection c = DriverManager.getConnection(urlDa(base), USER, PASSWORD)) {
                // Uma base "existente": o schema já lá está (aplicado fora do Flyway, como o Prisma faria) e sem histórico.
                try (Statement st = c.createStatement()) {
                    // O ficheiro inteiro de uma vez (um splitter partiria o corpo das funções $$...$$ nos `;`). Deixa o search_path vazio nesta sessão, por isso os nomes seguintes levam "public.".
                    st.execute(new String(new ClassPathResource("db/migration/V1__baseline.sql").getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                    st.execute("CREATE TABLE public.marcador_dados (id int)");
                    st.execute("INSERT INTO public.marcador_dados VALUES (42)");
                }
            } catch (SQLException | IOException e) {
                throw new IllegalStateException(e);
            }

            Flyway flyway = flywayComAConfigDaAplicacao(base);
            var resultado = flyway.migrate(); // com baseline-version 0 isto falhava: "relation ... already exists"

            assertThat(resultado.migrationsExecuted).as("nada deve ser reexecutado sobre um schema existente").isZero();
            MigrationInfo actual = flyway.info().current();
            assertThat(actual.getVersion().getVersion()).isEqualTo("1");
            try (Connection c = DriverManager.getConnection(urlDa(base), USER, PASSWORD); Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id FROM marcador_dados")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).as("os dados existentes ficam intactos").isEqualTo(42);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void configuracaoDoFlywayNoApplicationYml() {
        Properties cfg = configDoYaml();
        assertThat(cfg.getProperty("spring.flyway.baseline-on-migrate")).isEqualTo("true");
        // Tem de ser a versão do V1: com 0, uma base existente reaplicava o V1 e falhava.
        assertThat(cfg.getProperty("spring.flyway.baseline-version")).isEqualTo("1");
    }
}
