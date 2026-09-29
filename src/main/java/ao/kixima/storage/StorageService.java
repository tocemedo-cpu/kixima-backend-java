package ao.kixima.storage;

import ao.kixima.common.error.AppException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Espelha backend/src/services/storageService.js — armazenamento plugável de
 * ficheiros carregados: disco local ({@code /api/uploads/...}) ou um bucket
 * S3-compatível (AWS S3 / Supabase Storage / R2 / MinIO) via SDK da AWS.
 *
 * Tal como o Node: com {@code STORAGE_PROVIDER=s3} mas credenciais em falta,
 * QUALQUER upload cai para o disco local com um aviso ALTO no log — nunca
 * silencioso — porque o disco do contentor é apagado a cada reinício; a
 * Prontidão diz o NOME das variáveis que faltam. Uma falha do bucket é um
 * 502 com o motivo explicado, nunca um ficheiro "guardado" que não existe.
 */
@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);
    private static final Pattern EXT = Pattern.compile("\\.[a-z0-9]+$");
    private static final Pattern NOME_INVALIDO = Pattern.compile("[^a-z0-9-]", Pattern.CASE_INSENSITIVE);
    static final String PASTA_POR_OMISSAO = "products";

    private final String provider;
    private final Path uploadsDir;
    private final String bucket;
    private final String region;
    private final String accessKey;
    private final String secretKey;
    private final String endpoint;
    private final String publicUrl;
    private final boolean forcePathStyle;
    private volatile S3Client s3Client;

    public StorageService(@Value("${kixima.storage.provider:local}") String provider,
                           @Value("${kixima.storage.local-dir:uploads}") String localDir,
                           @Value("${kixima.storage.bucket:}") String bucket,
                           @Value("${kixima.storage.access-key:}") String accessKey,
                           @Value("${kixima.storage.secret-key:}") String secretKey,
                           @Value("${kixima.storage.region:}") String region,
                           @Value("${kixima.storage.endpoint:}") String endpoint,
                           @Value("${kixima.storage.public-url:}") String publicUrl,
                           @Value("${kixima.storage.force-path-style:true}") boolean forcePathStyle) {
        this.provider = provider == null ? "local" : provider.trim();
        this.uploadsDir = Paths.get(localDir).toAbsolutePath().normalize();
        this.bucket = limpar(bucket);
        this.accessKey = limpar(accessKey);
        this.secretKey = limpar(secretKey);
        this.region = limpar(region);
        this.endpoint = limpar(endpoint).replaceAll("/+$", "");
        this.publicUrl = limpar(publicUrl).replaceAll("/+$", "");
        this.forcePathStyle = forcePathStyle;
        if (s3MalConfigurado()) {
            log.error("Armazenamento S3 ATIVO mas mal configurado — faltam: {}. Os ficheiros vão para o disco do contentor, "
                    + "que é APAGADO a cada reinício. Defina essas variáveis no ambiente (Supabase → Project Settings → Storage → "
                    + "S3 access keys) e reinicie o serviço. Nota: uma variável criada mas deixada EM BRANCO conta como ausente. "
                    + "Para confirmar, entre como Admin do Sistema em Configurações e Suporte → Prontidão para produção.",
                    String.join(", ", emFalta()));
        }
    }

    private static String limpar(String v) {
        return v == null ? "" : v.trim();
    }

    private static String extDe(String originalname) {
        if (originalname == null) return ".jpg";
        Matcher m = EXT.matcher(originalname.toLowerCase());
        return m.find() ? m.group() : ".jpg";
    }

    private static String buildFilename(String keyHint, String originalname) {
        String id = NOME_INVALIDO.matcher(keyHint == null ? "img" : keyHint).replaceAll("");
        if (id.isBlank()) id = "img";
        return id + "-" + Instant.now().toEpochMilli() + extDe(originalname);
    }

    // --- Estado da configuração ---------------------------------------------------

    /** O provider PEDIDO no ambiente (STORAGE_PROVIDER) — distinto de {@link #providerAtivo()}. */
    public String providerConfigurado() {
        return provider;
    }

    public String bucket() {
        return bucket;
    }

    /** Espelha `config.storage.missing` — NOMES das variáveis obrigatórias em falta com S3 (nunca valores). */
    public List<String> emFalta() {
        if (!"s3".equals(provider)) return List.of();
        List<String> falta = new ArrayList<>();
        if (bucket.isBlank()) falta.add("STORAGE_BUCKET");
        if (accessKey.isBlank()) falta.add("STORAGE_ACCESS_KEY");
        if (secretKey.isBlank()) falta.add("STORAGE_SECRET_KEY");
        return falta;
    }

    private boolean s3MalConfigurado() {
        return "s3".equals(provider) && !emFalta().isEmpty();
    }

    /** 's3' só quando pedido E completo; senão 'local' — o mesmo `providerAtivo()` do Node. */
    public String providerAtivo() {
        return "s3".equals(provider) && !s3MalConfigurado() ? "s3" : "local";
    }

    // --- S3 -----------------------------------------------------------------------------

    private S3Client s3() {
        S3Client c = s3Client;
        if (c == null) {
            synchronized (this) {
                c = s3Client;
                if (c == null) {
                    S3ClientBuilder b = S3Client.builder()
                            .region(Region.of(region.isBlank() ? "us-east-1" : region))
                            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                            .forcePathStyle(forcePathStyle)
                            .httpClientBuilder(ApacheHttpClient.builder()
                                    .connectionTimeout(Duration.ofSeconds(10))
                                    .socketTimeout(Duration.ofSeconds(60)))
                            .overrideConfiguration(o -> o.retryStrategy(AwsRetryStrategy.doNotRetry()));
                    if (!endpoint.isBlank()) b.endpointOverride(URI.create(endpoint));
                    c = b.build();
                    s3Client = c;
                }
            }
        }
        return c;
    }

    /** URL público de um objeto: CDN/bucket público configurado, senão endpoint/bucket/key, senão o host AWS. */
    String publicUrlFor(String key, String bucketAlvo) {
        String b = bucketAlvo == null || bucketAlvo.isBlank() ? bucket : bucketAlvo;
        if (!publicUrl.isBlank() && b.equals(bucket)) return publicUrl + "/" + key;
        if (!endpoint.isBlank()) return endpoint + "/" + b + "/" + key;
        return "https://" + b + ".s3." + (region.isBlank() ? "us-east-1" : region) + ".amazonaws.com/" + key;
    }

    /** O erro cru do SDK não diz o que fazer — estes cobrem quase tudo. */
    String explicar(Throwable err) {
        StringBuilder mensagens = new StringBuilder();
        for (Throwable t = err; t != null; t = t.getCause()) {
            if (t.getMessage() != null) mensagens.append(t.getMessage()).append(" | ");
            if (t.getCause() == t) break;
        }
        String m = mensagens.toString();
        if (m.matches("(?is).*credential.*")) return "as credenciais S3 foram recusadas — verifique STORAGE_ACCESS_KEY e STORAGE_SECRET_KEY";
        if (m.matches("(?is).*SignatureDoesNotMatch.*")) return "a assinatura não confere — a chave secreta ou a região (STORAGE_REGION) estão erradas";
        if (m.matches("(?is).*(NoSuchBucket|NotFound|Not Found).*")) return "o bucket \"" + bucket + "\" não existe no endpoint configurado";
        if (m.matches("(?is).*(AccessDenied|Forbidden).*")) return "a chave não tem permissão de escrita neste bucket";
        if (m.matches("(?is).*(UnknownHost|Connection refused|ConnectException|Connect to .* failed|timed out|Temporary failure in name resolution).*")) {
            return "o endpoint \"" + (endpoint.isBlank() ? "AWS S3" : endpoint) + "\" está inacessível";
        }
        return err.getMessage() == null ? String.valueOf(err) : err.getMessage();
    }

    private String enviarS3(String key, byte[] buffer, String mimetype, String bucketAlvo) {
        try {
            s3().putObject(PutObjectRequest.builder()
                    .bucket(bucketAlvo)
                    .key(key)
                    .contentType(mimetype == null || mimetype.isBlank() ? "application/octet-stream" : mimetype)
                    .cacheControl("public, max-age=31536000, immutable")
                    .build(), RequestBody.fromBytes(buffer));
            return publicUrlFor(key, bucketAlvo);
        } catch (Exception err) {
            String motivo = explicar(err);
            log.error("Armazenamento S3: falha ao enviar {} — {}", key, motivo);
            throw new AppException("Não foi possível guardar o ficheiro no armazenamento: " + motivo + ".", 502, "STORAGE_ERROR");
        }
    }

    // --- Gravar / ler ----------------------------------------------------------------------

    /** Devolve o URL a guardar (ex.: {@code /api/uploads/produto-1699999999999.jpg}) — pasta 'products' por omissão, como o Node. */
    public String saveFile(byte[] buffer, String originalname, String mimetype, String keyHint) {
        return saveFile(buffer, originalname, mimetype, keyHint, PASTA_POR_OMISSAO);
    }

    /** `saveFile({ ..., folder })` — a pasta só tem significado no S3 (prefixo da chave). */
    public String saveFile(byte[] buffer, String originalname, String mimetype, String keyHint, String folder) {
        return saveFileComChave(buffer, originalname, mimetype, keyHint, folder, null).url();
    }

    /** Espelha `comChave: true` — quem guarda cópias de segurança precisa da CHAVE para as voltar a ler. */
    public record Guardado(String url, String key) {
    }

    /** `saveFile({ ..., folder, bucket, comChave: true })`. No provider local a chave é o próprio nome do ficheiro. */
    public Guardado saveFileComChave(byte[] buffer, String originalname, String mimetype, String keyHint,
                                     String folder, String bucketAlvo) {
        FileSignature.verificar(buffer, mimetype, originalname);
        String filename = buildFilename(keyHint, originalname);
        if ("s3".equals(providerAtivo())) {
            String alvo = bucketAlvo == null || bucketAlvo.isBlank() ? bucket : bucketAlvo;
            String key = (folder == null || folder.isBlank() ? PASTA_POR_OMISSAO : folder) + "/" + filename;
            log.info("Storage S3: a enviar {} para o bucket {}", key, alvo);
            return new Guardado(enviarS3(key, buffer, mimetype, alvo), key);
        }
        try {
            Files.createDirectories(uploadsDir);
            Files.write(uploadsDir.resolve(filename), buffer);
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível guardar o ficheiro no disco local.", e);
        }
        return new Guardado("/api/uploads/" + filename, filename);
    }

    /** Lê um objeto de volta do armazenamento — no S3 pela chave/bucket, no disco local pelo nome (nunca um caminho). */
    public byte[] lerFicheiro(String key, String bucketAlvo) throws IOException {
        if (!"s3".equals(providerAtivo())) return readFile(key);
        try {
            return s3().getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucketAlvo == null || bucketAlvo.isBlank() ? bucket : bucketAlvo)
                    .key(key)
                    .build()).asByteArray();
        } catch (Exception err) {
            throw new AppException("Não foi possível ler \"" + key + "\": " + explicar(err) + ".", 502, "STORAGE_ERROR");
        }
    }

    /** Lê um ficheiro já guardado no disco local — {@code filename}, nunca um caminho completo (só nome+extensão). */
    public byte[] readFile(String filename) throws IOException {
        return Files.readAllBytes(uploadsDir.resolve(Paths.get(filename).getFileName()));
    }

    /**
     * Espelha overrideIsAlive() de supportRoutes.js — um URL local só vale se o
     * ficheiro ainda estiver no disco (o Render apaga /api/uploads a cada deploy);
     * um URL remoto (https://...) assume-se vivo.
     */
    public boolean urlAindaVivo(String url) {
        if (url == null || url.isBlank()) return false;
        if (url.startsWith("/api/uploads/")) {
            String nome = url.substring("/api/uploads/".length());
            return !nome.isBlank() && Files.exists(uploadsDir.resolve(Paths.get(nome).getFileName()));
        }
        return true;
    }

    String getProvider() {
        return provider;
    }
}
