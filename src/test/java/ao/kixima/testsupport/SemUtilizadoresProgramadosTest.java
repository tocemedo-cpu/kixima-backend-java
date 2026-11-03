package ao.kixima.testsupport;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regra do projecto: NENHUM utilizador de teste pode estar programado — nem no código, nem nas migrações, nem nos
 * seeds, nem na configuração. Os testes criam os seus utilizadores durante a execução ({@link Contas}).
 *
 * Este teste percorre os ficheiros do projecto e falha se encontrar:
 * <ul>
 *   <li>uma das contas e senhas que existiam nos dados de exemplo (os padrões são montados em pedaços para este
 *   ficheiro não se denunciar a si próprio);</li>
 *   <li>um {@code INSERT INTO users} / {@code COPY users} em migrações ou noutro SQL da aplicação.</li>
 * </ul>
 * A lista de senhas proibidas em {@code PasswordPolicy} (deny-list, em minúsculas) não é um utilizador e não conta.
 */
class SemUtilizadoresProgramadosTest {

    private static final List<Pattern> CONTAS_E_SENHAS_FIXAS = List.of(
            Pattern.compile("[A-Za-z0-9._-]+@petro" + "angola\\.co\\.ao"),
            Pattern.compile("[A-Za-z0-9._-]+@kianda" + "\\.co\\.ao"),
            Pattern.compile("admin@kixima" + "\\.co\\.ao"),
            // senha dos dados de exemplo antigos (com K maiúsculo; a deny-list da política tem-na em minúsculas)
            Pattern.compile("Kixima" + "@123"),
            // os mesmos, em JSON/escapes
            Pattern.compile("comprador@petro" + "angola"));

    private static final Pattern INSERE_UTILIZADORES = Pattern.compile(
            "(?i)(insert\\s+into\\s+(public\\.)?\"?users\"?\\b|copy\\s+(public\\.)?\"?users\"?\\b)");

    private static final Path RAIZ = Paths.get("").toAbsolutePath();

    @Test
    void nenhumaContaOuSenhaDeTesteFicaProgramadaNoProjecto() throws IOException {
        List<String> achados = new ArrayList<>();
        for (String pasta : List.of("src", "docs", "README.md", "docker-compose.prod.yml", "docker-stack.swarm.yml", "Dockerfile", "pom.xml")) {
            Path p = RAIZ.resolve(pasta);
            if (!Files.exists(p)) continue;
            try (Stream<Path> ficheiros = Files.isDirectory(p) ? Files.walk(p) : Stream.of(p)) {
                for (Path f : (Iterable<Path>) ficheiros.filter(Files::isRegularFile).filter(SemUtilizadoresProgramadosTest::ehTexto)::iterator) {
                    if (f.getFileName().toString().equals("SemUtilizadoresProgramadosTest.java")) continue;
                    String texto = Files.readString(f);
                    for (Pattern padrao : CONTAS_E_SENHAS_FIXAS) {
                        if (padrao.matcher(texto).find()) achados.add(RAIZ.relativize(f) + "  (conta/senha fixa)");
                    }
                }
            }
        }
        assertThat(achados).as("ficheiros com contas ou senhas de teste programadas").isEmpty();
    }

    @Test
    void nenhumaMigracaoNemSqlDaAplicacaoInsereUtilizadores() throws IOException {
        List<String> achados = new ArrayList<>();
        Path recursos = RAIZ.resolve("src/main/resources");
        try (Stream<Path> ficheiros = Files.walk(recursos)) {
            for (Path f : (Iterable<Path>) ficheiros.filter(Files::isRegularFile).filter(x -> x.toString().endsWith(".sql"))::iterator) {
                if (INSERE_UTILIZADORES.matcher(Files.readString(f)).find()) achados.add(RAIZ.relativize(f).toString());
            }
        }
        assertThat(achados).as("SQL da aplicação (migrações incluídas) que insere utilizadores").isEmpty();
    }

    private static boolean ehTexto(Path f) {
        String n = f.getFileName().toString();
        return n.endsWith(".java") || n.endsWith(".sql") || n.endsWith(".yml") || n.endsWith(".yaml") || n.endsWith(".md")
                || n.endsWith(".properties") || n.endsWith(".xml") || n.endsWith(".json") || n.equals("Dockerfile");
    }
}
