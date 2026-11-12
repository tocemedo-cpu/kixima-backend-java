package ao.kixima.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O JAR de produção leva o que está em {@code src/main/resources}. A configuração do perfil {@code test} (JWT de teste,
 * chave ERP de teste, base {@code kixima_test} com credenciais descartáveis) tem de viver em {@code src/test/resources}:
 * fora do artefacto, nunca ao lado do perfil {@code prod}.
 */
class PerfilDeTesteForaDoArtefactoTest {

    private static final Path RAIZ = Paths.get("").toAbsolutePath();

    @Test
    void osPerfisDoArtefactoSaoSoDevEProd() throws IOException {
        List<String> perfis;
        try (Stream<Path> f = Files.list(RAIZ.resolve("src/main/resources"))) {
            perfis = f.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("application") && n.endsWith(".yml")).sorted().toList();
        }
        assertThat(perfis).as("ficheiros de configuração empacotados no JAR")
                .containsExactly("application-dev.yml", "application-prod.yml", "application.yml");
    }

    @Test
    void oPerfilDeTesteEstaEmSrcTestEnaoEmSrcMain() {
        assertThat(RAIZ.resolve("src/test/resources/application-test.yml")).isRegularFile();
        assertThat(RAIZ.resolve("src/main/resources/application-test.yml")).doesNotExist();
    }
}
