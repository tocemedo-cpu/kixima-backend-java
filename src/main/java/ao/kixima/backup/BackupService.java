package ao.kixima.backup;

import ao.kixima.audit.Actor;
import ao.kixima.audit.AuditLogRepository;
import ao.kixima.audit.AuditService;
import ao.kixima.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.zip.GZIPOutputStream;

/**
 * Espelha backend/src/jobs/backupJob.js (a parte que não é agendamento) —
 * cópia de segurança de dentro do próprio serviço: {@code pg_dump} → gzip →
 * armazenamento, registada no trilho de auditoria (o registo do serviço é
 * rotativo; "quando é que a última cópia correu mesmo?" tem de ter resposta
 * meses depois).
 *
 * A cópia só pode ir para S3 ({@link #motivoParaNaoCorrer()}): no disco do
 * contentor desaparece com ele — pior do que não ter cópia, porque dá falsa
 * segurança. Enquanto o provider S3 não estiver portado para Java (ver
 * {@link StorageService}), esta guarda recusa sempre, com a mesma mensagem
 * que o Node dá sem S3 — o mesmo comportamento de um Node sem STORAGE_*.
 * {@link #copiar()} em si corre com qualquer provider (é assim que os
 * testes o exercitam, tal como no Node).
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    static final String ACAO_CONCLUIDA = "COPIA_SEGURANCA_CONCLUIDA";
    static final String ACAO_FALHOU = "COPIA_SEGURANCA_FALHOU";
    private static final Actor SISTEMA = new Actor(null, "Sistema", null, null, null);
    private static final DateTimeFormatter NOME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss").withZone(ZoneOffset.UTC);

    public record Resultado(String destino, String key, long bytes, double segundos) {
        public double megabytes() {
            return Math.round(bytes / 1024.0 / 1024.0 * 100) / 100.0;
        }
    }

    /** O que verificarAtraso decidiu — para ser testável e para o log dizer porquê. */
    public record Atraso(boolean corrida, String motivo, Double horas, Double megabytes) {
    }

    private final StorageService storageService;
    private final AuditService auditService;
    private final AuditLogRepository auditLogRepository;
    private final String cron;
    private final int idadeMaximaHoras;
    private final String directUrl;
    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPassword;
    private final String bucket;
    private final String backupBucket;

    private final Object lock = new Object();
    private CompletableFuture<Resultado> emCurso;

    public BackupService(StorageService storageService, AuditService auditService, AuditLogRepository auditLogRepository,
                          @Value("${kixima.backup.cron:}") String cron,
                          @Value("${kixima.backup.max-idade-horas:26}") int idadeMaximaHoras,
                          @Value("${kixima.backup.direct-url:}") String directUrl,
                          @Value("${spring.datasource.url}") String jdbcUrl,
                          @Value("${spring.datasource.username:}") String dbUser,
                          @Value("${spring.datasource.password:}") String dbPassword,
                          @Value("${kixima.storage.bucket:}") String bucket,
                          @Value("${kixima.storage.backup-bucket:}") String backupBucket) {
        this.storageService = storageService;
        this.auditService = auditService;
        this.auditLogRepository = auditLogRepository;
        this.cron = limparValor(cron);
        this.idadeMaximaHoras = idadeMaximaHoras;
        this.directUrl = limparValor(directUrl);
        this.jdbcUrl = jdbcUrl;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        this.bucket = bucket;
        this.backupBucket = backupBucket;
    }

    // --- Leitura de variáveis coladas do painel (env.js: limpar/precisouDeLimpeza) ---

    /** Fica a última linha com conteúdo, sem aspas à volta — o painel guarda o que lá for colado, rótulo incluído. */
    public static String limparValor(String valor) {
        if (valor == null) return "";
        String ultima = "";
        for (String linha : valor.split("\\r?\\n")) {
            String l = linha.trim();
            if (!l.isEmpty()) ultima = l;
        }
        return ultima.replaceAll("^[\"']|[\"']$", "").trim();
    }

    public static boolean precisouDeLimpeza(String valor) {
        return valor != null && !valor.isEmpty() && !valor.equals(limparValor(valor));
    }

    /** node-cron usa 5 campos (min hora dia mês dow); o Spring exige 6 (com segundos) — "0 3 * * *" → "0 0 3 * * *". */
    public static String normalizarCron(String expressao) {
        String[] campos = expressao.trim().split("\\s+");
        return campos.length == 5 ? "0 " + expressao.trim() : expressao.trim();
    }

    public static boolean cronValido(String expressao) {
        return CronExpression.isValidExpression(normalizarCron(expressao));
    }

    public String cron() {
        return cron;
    }

    public int idadeMaximaHoras() {
        return idadeMaximaHoras;
    }

    public String bucket() {
        return bucket;
    }

    public String backupBucket() {
        return backupBucket;
    }

    /** A ligação libpq (DIRECT_URL) tal como chegou ao processo — contém a senha; só para uso interno, nunca para uma resposta. */
    public String directUrl() {
        return directUrl;
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    // --- Guardas ---------------------------------------------------------------

    /**
     * Porque é que uma cópia NÃO pode correr agora — ou null, se puder. A
     * mesma função serve o agendamento e o botão "Fazer cópia agora": se o
     * botão aceitasse condições que o agendamento recusa, daria por
     * confirmado um caminho que à noite não existe.
     */
    public String motivoParaNaoCorrer() {
        if (!"s3".equals(storageService.providerAtivo())) {
            return "Sem armazenamento S3 configurado. Uma cópia no disco do contentor desaparece com ele, "
                    + "o que é pior do que não ter cópia: dá a impressão de estar protegido. Configure STORAGE_* e reinicie.";
        }
        if (backupBucket == null || backupBucket.isBlank()) {
            return "STORAGE_BACKUP_BUCKET em falta. As cópias NÃO podem ir para o bucket das imagens: esse é "
                    + "público (é de lá que o marketplace serve as fotos do catálogo), e um dump da base tem hashes de "
                    + "senha, os dados de todas as empresas e o histórico financeiro. Crie um bucket PRIVADO só para cópias.";
        }
        if (backupBucket.equals(bucket)) {
            return "STORAGE_BACKUP_BUCKET é o MESMO bucket das imagens, que é público. "
                    + "As cópias precisam de um bucket privado só delas.";
        }
        return null;
    }

    // --- A cópia ---------------------------------------------------------------

    /** Espelha urlDeDump: DIRECT_URL/DATABASE_URL; sem elas, a ligação JDBC configurada, em formato libpq. */
    String urlDeDump() {
        if (!directUrl.isBlank()) return directUrl;
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) return null;
        URI u = URI.create(jdbcUrl.substring("jdbc:".length()));
        String credenciais = dbUser == null || dbUser.isBlank() ? ""
                : URLEncoder.encode(dbUser, StandardCharsets.UTF_8)
                + (dbPassword == null || dbPassword.isEmpty() ? "" : ":" + URLEncoder.encode(dbPassword, StandardCharsets.UTF_8)) + "@";
        return u.getScheme() + "://" + credenciais + u.getRawAuthority() + u.getRawPath()
                + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
    }

    /**
     * Uma cópia de cada vez: o pg_dump carrega a base inteira em memória, e
     * duas em paralelo (o agendamento e alguém a carregar no botão) derrubavam
     * o serviço. Quem chega a meio recebe a que já está a correr.
     */
    public Resultado copiar() {
        CompletableFuture<Resultado> futuro;
        boolean dono = false;
        synchronized (lock) {
            if (emCurso == null) {
                emCurso = new CompletableFuture<>();
                dono = true;
            }
            futuro = emCurso;
        }
        if (!dono) {
            try {
                return futuro.join();
            } catch (CompletionException e) {
                throw e.getCause() instanceof RuntimeException re ? re : new IllegalStateException(e.getCause());
            }
        }
        try {
            Resultado r = executar();
            futuro.complete(r);
            return r;
        } catch (RuntimeException e) {
            futuro.completeExceptionally(e);
            throw e;
        } finally {
            synchronized (lock) {
                emCurso = null;
            }
        }
    }

    private Resultado executar() {
        String url = urlDeDump();
        if (url == null || url.isBlank()) throw new IllegalStateException("DIRECT_URL/DATABASE_URL não definido");

        long inicio = System.currentTimeMillis();
        byte[] dump = correrPgDump(url);

        byte[] comprimido;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            try (GZIPOutputStream gz = new GZIPOutputStream(out) {
                { def.setLevel(9); }
            }) {
                gz.write(dump);
            }
            comprimido = out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Falha a comprimir a cópia: " + e.getMessage(), e);
        }

        String nome = "kixima-" + NOME.format(Instant.now()) + ".sql.gz";
        StorageService.Guardado guardado = storageService.saveFileComChave(comprimido, nome, "application/gzip",
                "kixima-backup", "backups", backupBucket);

        Resultado resultado = new Resultado(guardado.url(), guardado.key(), comprimido.length,
                (System.currentTimeMillis() - inicio) / 1000.0);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("megabytes", resultado.megabytes());
        detail.put("segundos", Math.round(resultado.segundos() * 10) / 10.0);
        detail.put("bucket", backupBucket == null || backupBucket.isBlank() ? null : backupBucket);
        // A chave é o que permite voltar a LER a cópia mais tarde para confirmar que está inteira.
        detail.put("chave", guardado.key());
        registar(ACAO_CONCLUIDA, detail);
        return resultado;
    }

    private byte[] correrPgDump(String url) {
        ProcessBuilder pb = new ProcessBuilder("pg_dump", "--no-owner", "--no-privileges", "--clean", "--if-exists", url);
        pb.redirectErrorStream(false);
        try {
            Process p = pb.start();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            Thread leitorDeErros = new Thread(() -> {
                try (InputStream err = p.getErrorStream()) {
                    err.transferTo(stderr);
                } catch (IOException ignorado) {
                    // o que importa é o stdout e o código de saída
                }
            });
            leitorDeErros.start();
            byte[] stdout;
            try (InputStream in = p.getInputStream()) {
                stdout = in.readAllBytes();
            }
            int codigo = p.waitFor();
            leitorDeErros.join();
            if (codigo != 0) {
                throw new IllegalStateException("pg_dump terminou com código " + codigo + ": "
                        + stderr.toString(StandardCharsets.UTF_8).trim());
            }
            return stdout;
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível correr o pg_dump: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("pg_dump interrompido.", e);
        }
    }

    public void registar(String action, Map<String, Object> detail) {
        Object bucketDoRegisto = detail.get("bucket");
        auditService.recordSafe(new AuditService.Entry(SISTEMA, action, "Backup", null,
                bucketDoRegisto == null ? null : bucketDoRegisto.toString(), detail));
    }

    // --- Recuperação de cópias perdidas ------------------------------------------

    public Instant ultimaCopiaComSucesso() {
        try {
            return auditLogRepository.findFirstByActionOrderByCreatedAtDesc(ACAO_CONCLUIDA).map(a -> a.getCreatedAt()).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Corre uma cópia se a última for demasiado antiga (ou não existir). Em
     * vez de "às 03:00", a pergunta é "já passou demasiado tempo desde a
     * última?" — que pode ser feita sempre que o serviço estiver acordado.
     */
    public Atraso verificarAtraso() {
        if (cron.isBlank()) return new Atraso(false, "sem BACKUP_CRON", null, null);
        String impedimento = motivoParaNaoCorrer();
        if (impedimento != null) return new Atraso(false, impedimento, null, null);

        Instant ultima = ultimaCopiaComSucesso();
        double horas = ultima == null ? Double.POSITIVE_INFINITY : (System.currentTimeMillis() - ultima.toEpochMilli()) / 36e5;
        if (horas < idadeMaximaHoras) {
            return new Atraso(false, "em dia", Math.round(horas * 10) / 10.0, null);
        }

        log.info(ultima != null
                ? String.format("Cópia de segurança em atraso — a última foi há %.1fh (limite %dh). A copiar agora.", horas, idadeMaximaHoras)
                : "Ainda não existe nenhuma cópia de segurança. A copiar agora.");
        try {
            Resultado r = copiar();
            log.info("Cópia de recuperação concluída — {} MB", r.megabytes());
            return new Atraso(true, null, null, r.megabytes());
        } catch (RuntimeException err) {
            log.error("FALHA NA CÓPIA DE RECUPERAÇÃO: {}", err.getMessage());
            registar(ACAO_FALHOU, Map.of("erro", resumo(err.getMessage())));
            return new Atraso(false, err.getMessage(), null, null);
        }
    }

    static String resumo(String erro) {
        String s = String.valueOf(erro);
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
