package ao.kixima.backup;

import ao.kixima.audit.AuditLog;
import ao.kixima.audit.AuditLogRepository;
import ao.kixima.common.error.BusinessRuleException;
import ao.kixima.storage.StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Espelha backend/src/services/backupVerificacaoService.js — a última cópia
 * ainda se LÊ? A escrita não responde a isto: um objeto truncado, um gzip
 * corrompido ou um bucket esvaziado por retenção são indistinguíveis até
 * alguém tentar ler. NÃO é um ensaio de restauro (esse faz-se fora da
 * plataforma) — confirma que o ficheiro existe, descomprime, é um dump de
 * PostgreSQL e traz todas as tabelas e dados que a base tem hoje.
 */
@Service
public class BackupVerificationService {

    private static final Pattern E_DUMP = Pattern.compile("PostgreSQL database dump", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATE_TABLE = Pattern.compile("^CREATE TABLE ", Pattern.MULTILINE);
    private static final Pattern COPY = Pattern.compile("^COPY .* FROM stdin;", Pattern.MULTILINE);
    private static final DateTimeFormatter QUANDO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    public record UltimaCopia(Instant quando, String chave, String bucket, Double megabytes) {
    }

    public record Verificacao(Instant quando, String bucket, Double megabytes, int tabelasNoDump, Integer tabelasNaBase,
                              int blocosDeDados, int linhasDeSql, double segundos, String nota) {
    }

    private final AuditLogRepository auditLogRepository;
    private final StorageService storageService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final String backupBucket;

    public BackupVerificationService(AuditLogRepository auditLogRepository, StorageService storageService,
                                      JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                      @Value("${kixima.storage.backup-bucket:}") String backupBucket) {
        this.auditLogRepository = auditLogRepository;
        this.storageService = storageService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.backupBucket = backupBucket;
    }

    /** Quantas tabelas a base tem agora — a referência contra a qual se mede o dump. */
    Integer tabelasNaBase() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*)::int FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
                Integer.class);
    }

    public UltimaCopia ultimaCopia() {
        AuditLog r = auditLogRepository.findFirstByActionOrderByCreatedAtDesc(BackupService.ACAO_CONCLUIDA).orElse(null);
        if (r == null) return null;
        JsonNode detail = null;
        try {
            detail = r.getDetail() == null ? null : objectMapper.readTree(r.getDetail());
        } catch (IOException ignorado) {
            // detail corrompido — trata-se como registo sem chave
        }
        String chave = detail != null && detail.hasNonNull("chave") ? detail.get("chave").asText() : null;
        String bucket = detail != null && detail.hasNonNull("bucket") ? detail.get("bucket").asText() : backupBucket;
        Double megabytes = detail != null && detail.hasNonNull("megabytes") ? detail.get("megabytes").asDouble() : null;
        return new UltimaCopia(r.getCreatedAt(), chave, bucket, megabytes);
    }

    /** Vai buscar a última cópia e confirma que está inteira — devolve os números, nunca um simples "ok". */
    public Verificacao verificar() {
        UltimaCopia ultima = ultimaCopia();
        if (ultima == null) {
            throw new BusinessRuleException("Ainda não há nenhuma cópia registada. Faça uma primeiro, com \"Fazer cópia agora\".");
        }
        if (ultima.chave() == null) {
            throw new BusinessRuleException("A última cópia foi feita antes de a chave do ficheiro passar a ser registada, "
                    + "por isso não há como a ir buscar. Faça uma cópia nova e verifique essa.");
        }

        long inicio = System.currentTimeMillis();
        byte[] comprimido;
        try {
            comprimido = storageService.lerFicheiro(ultima.chave(), ultima.bucket());
        } catch (IOException e) {
            throw new BusinessRuleException("Não foi possível ler \"" + ultima.chave() + "\": " + e.getMessage() + ".");
        }

        String sql;
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(comprimido))) {
            sql = new String(gz.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException err) {
            // Um gzip que não abre é uma cópia que não existe, por muito que o ficheiro esteja lá.
            throw new BusinessRuleException("A cópia de " + QUANDO.format(ultima.quando()) + " está CORROMPIDA — "
                    + "não descomprime (" + err.getMessage() + "). Faça uma cópia nova e verifique a origem do problema "
                    + "antes de confiar nas anteriores.");
        }

        boolean eDump = E_DUMP.matcher(sql).find();
        int tabelas = contar(CREATE_TABLE.matcher(sql));
        int blocosDeDados = contar(COPY.matcher(sql));
        Integer naBase = tabelasNaBase();

        List<String> problemas = new ArrayList<>();
        if (!eDump) problemas.add("o ficheiro não parece um dump de PostgreSQL");
        if (tabelas == 0) problemas.add("não contém nenhuma tabela");
        if (naBase != null && naBase > 0 && tabelas < naBase) {
            problemas.add("contém " + tabelas + " tabelas mas a base tem " + naBase + " — está incompleto");
        }
        if (blocosDeDados == 0) problemas.add("não contém dados, só a estrutura");

        if (!problemas.isEmpty()) {
            throw new BusinessRuleException("A cópia de " + QUANDO.format(ultima.quando()) + " NÃO serve: "
                    + String.join("; ", problemas) + ".");
        }

        return new Verificacao(ultima.quando(), ultima.bucket(), ultima.megabytes(), tabelas, naBase, blocosDeDados,
                sql.split("\n", -1).length, Math.round((System.currentTimeMillis() - inicio) / 100.0) / 10.0,
                "A cópia foi lida do bucket, descomprimiu e está completa. Isto NÃO é um ensaio de "
                        + "restauro: repor o dump numa base e comparar linha a linha faz-se com "
                        + "`npm run backup:restore-test`, fora da plataforma.");
    }

    private static int contar(Matcher m) {
        int n = 0;
        while (m.find()) n++;
        return n;
    }
}
