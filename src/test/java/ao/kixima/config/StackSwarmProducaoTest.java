package ao.kixima.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stack Swarm de referência (docker-stack.swarm.yml) corre o backend em PRODUÇÃO sem expor segredos: perfil {@code prod},
 * a base {@code db_kixima:5432/kixima} com o utilizador {@code kixima}, e a senha, o JWT e as chaves S3 como secrets do
 * Swarm montados em /run/secrets (lidos por {@code configtree}) — nunca como variáveis de ambiente do serviço, que
 * aparecem em {@code docker service inspect}.
 */
class StackSwarmProducaoTest {

    /** Variáveis cujo valor é um segredo: não podem estar em {@code environment} (a convenção {@code *_FILE} é aceite). */
    private static final Pattern SEGREDO = Pattern.compile("(PASSWORD|SECRET|TOKEN|ACCESS_KEY|API_KEY)(?!.*_FILE$)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> SECRETS_DO_BACKEND = Set.of("DATABASE_PASSWORD", "JWT_SECRET", "STORAGE_ACCESS_KEY", "STORAGE_SECRET_KEY");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stack() throws IOException {
        return (Map<String, Object>) new Yaml().load(Files.readString(Path.of("docker-stack.swarm.yml")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> servico(Map<String, Object> stack, String nome) {
        return (Map<String, Object>) ((Map<String, Object>) stack.get("services")).get(nome);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ambiente(Map<String, Object> servico) {
        return (Map<String, Object>) servico.get("environment");
    }

    @Test
    void obackendCorreEmProducaoLigadoAbaseKixima() throws IOException {
        Map<String, Object> env = ambiente(servico(stack(), "kixima"));
        assertThat(env.get("SPRING_PROFILES_ACTIVE")).isEqualTo("prod");
        assertThat(env.get("DATABASE_URL_JDBC")).isEqualTo("jdbc:postgresql://db_kixima:5432/kixima");
        assertThat(env.get("DATABASE_USER")).isEqualTo("kixima");
        assertThat(env.get("SPRING_CONFIG_IMPORT")).isEqualTo("configtree:/run/secrets/");
    }

    @Test
    void nenhumSegredoEstaEmVariaveisDeAmbienteDeNenhumServico() throws IOException {
        Map<String, Object> stack = stack();
        for (String nome : List.of("kixima", "db_kixima")) {
            for (String chave : ambiente(servico(stack, nome)).keySet()) {
                assertThat(SEGREDO.matcher(chave).find()).as("%s: variável «%s» parece um segredo em claro", nome, chave).isFalse();
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void senhaJwtEChavesS3VemDeSecretsDoSwarmComOsNomesQueOPerfilProdEspera() throws IOException {
        Map<String, Object> stack = stack();
        Map<String, Object> declarados = (Map<String, Object>) stack.get("secrets");
        List<Map<String, Object>> montados = (List<Map<String, Object>>) servico(stack, "kixima").get("secrets");
        assertThat(montados.stream().map(s -> (String) s.get("target")).toList()).containsExactlyInAnyOrderElementsOf(SECRETS_DO_BACKEND);
        for (Map<String, Object> s : montados) {
            Map<String, Object> def = (Map<String, Object>) declarados.get((String) s.get("source"));
            assertThat(def).as("secret «%s» declarado", s.get("source")).isNotNull();
            assertThat(def.get("external")).as("secret «%s» externo (criado fora, nunca neste ficheiro)", s.get("source")).isEqualTo(true);
            assertThat(def).as("secret «%s» sem valor literal", s.get("source")).doesNotContainKeys("file", "content", "environment");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void aBaseLeASenhaDoMesmoSecretPorFicheiro() throws IOException {
        Map<String, Object> db = servico(stack(), "db_kixima");
        Map<String, Object> env = ambiente(db);
        assertThat(env).doesNotContainKey("POSTGRES_PASSWORD");
        assertThat(env.get("POSTGRES_PASSWORD_FILE")).isEqualTo("/run/secrets/kixima_database_password");
        assertThat(env.get("POSTGRES_USER")).isEqualTo("kixima");
        assertThat(env.get("POSTGRES_DB")).isEqualTo("kixima");
        assertThat((List<Object>) db.get("secrets")).contains("kixima_database_password");
    }

    @Test
    void asVariaveisObrigatoriasDoPerfilProdEstaoNaStack() throws IOException {
        Map<String, Object> env = ambiente(servico(stack(), "kixima"));
        assertThat(env.get("STORAGE_PROVIDER")).isEqualTo("s3");
        // Sem valor a stack recusa o deploy (sintaxe ${VAR:?mensagem}) em vez de arrancar mal configurada.
        assertThat((String) env.get("APP_URL")).startsWith("${APP_URL:?");
        assertThat((String) env.get("STORAGE_BUCKET")).startsWith("${STORAGE_BUCKET:?");
    }
}
