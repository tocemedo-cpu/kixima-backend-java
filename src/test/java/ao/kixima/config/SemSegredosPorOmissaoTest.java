package ao.kixima.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O repositório é PÚBLICO: o {@code application.yml} (que também vai dentro do JAR) não pode trazer segredos como valor
 * por omissão. Uma chave sensível lê-se do ambiente ({@code ${VAR:}}); sem a variável fica vazia e a aplicação recusa
 * arrancar ou desliga a funcionalidade. Os perfis {@code dev} e {@code test} trazem só o que serve a uma base local
 * descartável (ver README), nunca um segredo de produção.
 */
class SemSegredosPorOmissaoTest {

    /** Chaves cujo valor é um segredo. */
    private static final Pattern CHAVE_SENSIVEL = Pattern.compile("(password|secret|token|api-key|access-key|private-key)", Pattern.CASE_INSENSITIVE);
    /** {@code chave: ${VAR:valor por omissão}}. */
    private static final Pattern LINHA = Pattern.compile("^\\s*([A-Za-z0-9_.-]+):\\s*\\$\\{[A-Z0-9_]+:([^}]*)\\}\\s*(#.*)?$");

    @Test
    void chavesSensiveisDoApplicationYmlNaoTemValorPorOmissao() throws IOException {
        String yaml = new String(new ClassPathResource("application.yml").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        List<String> violacoes = new ArrayList<>();
        int n = 0;
        for (String linha : yaml.split("\n")) {
            n++;
            Matcher m = LINHA.matcher(linha);
            if (!m.matches()) continue;
            String chave = m.group(1);
            String omissao = m.group(2).trim();
            boolean sensivel = CHAVE_SENSIVEL.matcher(chave).find();
            boolean naoESegredo = chave.endsWith("-path") || omissao.matches("\\d+"); // um caminho de ficheiro; um limite numérico
            if (sensivel && !naoESegredo && !omissao.isEmpty()) {
                violacoes.add("application.yml:" + n + " — `" + chave + "` tem valor por omissão (não o escreva aqui; use a variável de ambiente)");
            }
        }
        assertThat(violacoes).as("segredos com valor por omissão num repositório público").isEmpty();
    }

    @Test
    void senhaDaBaseESegredoJwtLemSeDoAmbiente() throws IOException {
        String yaml = new String(new ClassPathResource("application.yml").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(yaml).containsPattern("password:\\s*\\$\\{DATABASE_PASSWORD:\\}");
        assertThat(yaml).containsPattern("jwt-secret:\\s*\\$\\{JWT_SECRET:\\}");
    }
}
