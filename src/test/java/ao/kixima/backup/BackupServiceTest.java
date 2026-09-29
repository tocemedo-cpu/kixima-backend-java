package ao.kixima.backup;

import ao.kixima.audit.AuditLogRepository;
import ao.kixima.audit.AuditService;
import ao.kixima.common.error.BusinessRuleException;
import ao.kixima.storage.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Teste de paridade de contrato para backupJob.js + backupVerificacaoService.js
 * (tests/backup.test.js, tests/backup-recuperacao.test.js). Ciclo completo
 * pg_dump → gzip → armazenamento → ler → descomprimir → confirmar que traz a
 * base toda, contra o provider de disco — no Render o troço do meio é o S3,
 * com a mesma chave e o mesmo conteúdo. E as recusas: sem S3, sem
 * BACKUP_CRON, cópia corrompida, dump vazio.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BackupServiceTest {

    @Autowired
    private BackupService backupService;

    @Autowired
    private BackupVerificationService verificacao;

    @Autowired
    private StorageService storageService;

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    @Value("${kixima.storage.local-dir:uploads}")
    private String localDir;

    private final List<String> ficheirosCriados = new ArrayList<>();

    @AfterEach
    void limparFicheiros() throws IOException {
        for (String key : ficheirosCriados) Files.deleteIfExists(Paths.get(localDir, key));
    }

    private String guardar(byte[] conteudo) {
        String key = storageService.saveFileComChave(conteudo, "lixo.sql.gz", "application/gzip", "kixima-backup-teste", "backups", null).key();
        ficheirosCriados.add(key);
        backupService.registar(BackupService.ACAO_CONCLUIDA, Map.of("megabytes", 0.01, "chave", key));
        return key;
    }

    private static byte[] gzip(String texto) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(texto.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    @Test
    void umaCopiaAcabadaDeFazerLeSeDescomprimeETrazABaseToda() {
        BackupService.Resultado r = backupService.copiar();
        ficheirosCriados.add(r.key());
        assertThat(r.bytes()).isGreaterThan(0);
        assertThat(r.key()).endsWith(".gz");

        BackupVerificationService.Verificacao v = verificacao.verificar();
        assertThat(v.tabelasNoDump()).isGreaterThan(0);
        assertThat(v.tabelasNaBase()).isGreaterThan(0);
        // `--clean --if-exists` acrescenta DROPs, mas os CREATE TABLE são um por tabela da base.
        assertThat(v.tabelasNoDump()).isGreaterThanOrEqualTo(v.tabelasNaBase());
        assertThat(v.blocosDeDados()).isGreaterThan(0);
        assertThat(v.nota()).contains("NÃO é um ensaio de restauro");
    }

    @Test
    void semNenhumaCopiaFeitaDizParaFazerUma() {
        assertThatThrownBy(() -> verificacao.verificar())
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("nenhuma cópia registada");
    }

    @Test
    void umFicheiroCorrompidoEApanhadoEDizQueEstaCorrompido() {
        guardar("isto não é um gzip".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> verificacao.verificar()).hasMessageContaining("CORROMPIDA");
    }

    @Test
    void umDumpSemTabelasNenhumasNaoPassaPorBom() throws IOException {
        guardar(gzip("--\n-- PostgreSQL database dump\n--\n"));
        assertThatThrownBy(() -> verificacao.verificar()).hasMessageContaining("não contém nenhuma tabela");
    }

    @Test
    void semS3RecusaSeECorrerAtrasoRecusaPelasMesmasRazoes() {
        // Perfil de testes: provider local → a mesma recusa que o agendamento daria.
        String motivo = backupService.motivoParaNaoCorrer();
        assertThat(motivo).containsAnyOf("disco do contentor", "pior do que não ter cópia");

        // Sem BACKUP_CRON (perfil de testes) não faz nada — continua a ser opt-in.
        BackupService.Atraso semCron = backupService.verificarAtraso();
        assertThat(semCron.corrida()).isFalse();
        assertThat(semCron.motivo()).contains("sem BACKUP_CRON");

        // Com BACKUP_CRON mas sem S3: o atraso é reconhecido, e o que o impede é o armazenamento — pelo mesmo motivo.
        BackupService comCron = new BackupService(storageService, auditService, auditLogRepository, "0 3 * * *", 26, "",
                jdbcUrl, "kixima", "kixima", "", "");
        BackupService.Atraso r = comCron.verificarAtraso();
        assertThat(r.corrida()).isFalse();
        assertThat(r.motivo()).isEqualTo(motivo);
        assertThat(r.megabytes()).isNull();
    }

    @Test
    void variaveisColadasDoPainelECronDe5Campos() {
        assertThat(BackupService.limparValor("Value:\n\"0 3 * * *\"\n")).isEqualTo("0 3 * * *");
        assertThat(BackupService.precisouDeLimpeza("\"0 3 * * *\"")).isTrue();
        assertThat(BackupService.precisouDeLimpeza("0 3 * * *")).isFalse();
        assertThat(BackupService.normalizarCron("0 3 * * *")).isEqualTo("0 0 3 * * *");
        assertThat(BackupService.cronValido("0 3 * * *")).isTrue();
        assertThat(BackupService.cronValido("todos os dias às 3")).isFalse();
        // O limite é configurável e tem um valor por omissão sensato.
        assertThat(backupService.idadeMaximaHoras()).isBetween(24, 48);
        // Sem DIRECT_URL, o pg_dump usa a ligação JDBC em formato libpq.
        assertThat(backupService.urlDeDump()).isEqualTo("postgresql://kixima:kixima@localhost:5432/kixima_test");
    }
}
