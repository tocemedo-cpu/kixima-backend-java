package ao.kixima.admin;

import ao.kixima.agt.AgtSigningService;
import ao.kixima.audit.AuditLog;
import ao.kixima.audit.AuditLogRepository;
import ao.kixima.backup.BackupService;
import ao.kixima.catalog.ProductDocumentRepository;
import ao.kixima.cobranca.AssinaturaService;
import ao.kixima.cobranca.CanaisPagamentoService;
import ao.kixima.cobranca.CanalCobranca;
import ao.kixima.cobranca.PlanoCobrancaRepository;
import ao.kixima.company.CompanyDocumentRepository;
import ao.kixima.company.CompanyRepository;
import ao.kixima.company.CompanyType;
import ao.kixima.company.SupplierToKiximaPolicyRepository;
import ao.kixima.notification.EmailDispatchService;
import ao.kixima.payment.PaymentRepository;
import ao.kixima.security.MfaPolicyService;
import ao.kixima.security.PersonaRole;
import ao.kixima.storage.StorageService;
import ao.kixima.user.User;
import ao.kixima.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Espelha backend/src/services/prontidaoService.js — o que está MESMO
 * configurado no ambiente em que este processo corre. As definições que
 * protegem a plataforma vivem em variáveis de ambiente noutro sítio, e uma que
 * falte não dá erro: a aplicação arranca e parece estar tudo bem. Este serviço
 * lê o que o processo tem realmente carregado e diz, por palavras, o que falta
 * e o que fazer.
 *
 * REGRA: nunca devolve o VALOR de um segredo. Diz que existe, que tamanho tem,
 * ou que está em falta — nunca o conteúdo.
 */
@Service
public class ProntidaoService {

    private static final Logger log = LoggerFactory.getLogger(ProntidaoService.class);

    public static final String OK = "ok";        // está feito
    public static final String AVISO = "aviso";  // funciona, mas não é o que se quer em produção
    public static final String FALHA = "falha";  // não funciona, ou funciona a fingir

    private static final String LOCAL = "/api/uploads/";
    private static final DateTimeFormatter MINUTO_UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);
    private static final Pattern HOST_PORTA_LIBPQ = Pattern.compile("@([^:/?]+)(?::(\\d+))?");
    private static final Pattern HOST_PORTA_JDBC = Pattern.compile("//([^:/?]+)(?::(\\d+))?");

    // Nome de exibição de cada canal automático, para a mensagem de ação.
    private static final Map<String, String> NOME_CANAL = Map.of(
            "EMIS_MULTICAIXA", "Multicaixa Express",
            "PAYPAY", "PayPay",
            "BAI", "BAI",
            "BFA", "BFA",
            "STANDARD_BANK_ANGOLA", "Standard Bank Angola");

    private final Environment environment;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final BackupService backupService;
    private final StorageService storageService;
    private final EmailDispatchService emailDispatchService;
    private final MfaPolicyService mfaPolicyService;
    private final AssinaturaService assinaturaService;
    private final CanaisPagamentoService canaisPagamentoService;
    private final AgtSigningService agtSigningService;
    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final PaymentRepository paymentRepository;
    private final PlanoCobrancaRepository planoCobrancaRepository;
    private final CompanyDocumentRepository companyDocumentRepository;
    private final SupplierToKiximaPolicyRepository policyRepository;
    private final ProductDocumentRepository productDocumentRepository;
    private final String jwtSecret;
    private final String appUrl;
    private final String nifKixima;
    private final String certificadoAgt;

    public ProntidaoService(Environment environment, JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                            BackupService backupService, StorageService storageService, EmailDispatchService emailDispatchService,
                            MfaPolicyService mfaPolicyService, AssinaturaService assinaturaService,
                            CanaisPagamentoService canaisPagamentoService, AgtSigningService agtSigningService,
                            AuditLogRepository auditLogRepository, UserRepository userRepository, CompanyRepository companyRepository,
                            PaymentRepository paymentRepository, PlanoCobrancaRepository planoCobrancaRepository,
                            CompanyDocumentRepository companyDocumentRepository, SupplierToKiximaPolicyRepository policyRepository,
                            ProductDocumentRepository productDocumentRepository,
                            @Value("${kixima.auth.jwt-secret:}") String jwtSecret,
                            @Value("${kixima.app-url:}") String appUrl,
                            @Value("${kixima.faturacao.nif:}") String nifKixima,
                            @Value("${kixima.faturacao.certificado-agt:}") String certificadoAgt) {
        this.environment = environment;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.backupService = backupService;
        this.storageService = storageService;
        this.emailDispatchService = emailDispatchService;
        this.mfaPolicyService = mfaPolicyService;
        this.assinaturaService = assinaturaService;
        this.canaisPagamentoService = canaisPagamentoService;
        this.agtSigningService = agtSigningService;
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
        this.companyRepository = companyRepository;
        this.paymentRepository = paymentRepository;
        this.planoCobrancaRepository = planoCobrancaRepository;
        this.companyDocumentRepository = companyDocumentRepository;
        this.policyRepository = policyRepository;
        this.productDocumentRepository = productDocumentRepository;
        this.jwtSecret = jwtSecret == null ? "" : jwtSecret;
        this.appUrl = appUrl == null ? "" : appUrl;
        this.nifKixima = nifKixima == null ? "" : nifKixima.trim();
        this.certificadoAgt = certificadoAgt == null ? "" : certificadoAgt.trim();
    }

    // --- Forma de cada verificação ------------------------------------------

    private static Map<String, Object> check(String id, String titulo, String estado, String detalhe, String acao) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("titulo", titulo);
        m.put("estado", estado);
        m.put("detalhe", detalhe);
        if (acao != null) m.put("acao", acao); // `acao: undefined` no Node desaparece do JSON
        return m;
    }

    private static Map<String, Object> check(String id, String titulo, String estado, String detalhe) {
        return check(id, titulo, estado, detalhe, null);
    }

    // --- Base de dados ----------------------------------------------------------

    record Ligacao(String host, Integer porta, boolean pgbouncer) {
    }

    /** A ligação da aplicação é JDBC (jdbc:postgresql://host:porta/base?...); a directa é libpq (postgres://user:senha@host:porta/base). */
    static Ligacao analisarLigacao(String url, boolean jdbc) {
        if (url == null || url.isBlank()) return null;
        Matcher m = (jdbc ? HOST_PORTA_JDBC : HOST_PORTA_LIBPQ).matcher(url);
        if (!m.find()) return new Ligacao(null, null, false);
        Integer porta = m.group(2) == null ? null : Integer.valueOf(m.group(2));
        // No JDBC o equivalente de `pgbouncer=true` (Prisma) é `prepareThreshold=0`: o pooler de
        // transação não aguenta prepared statements com nome.
        boolean pgbouncer = url.contains("pgbouncer=true") || url.contains("prepareThreshold=0");
        return new Ligacao(m.group(1), porta, pgbouncer);
    }

    private List<Map<String, Object>> verBaseDeDados() {
        Ligacao app = analisarLigacao(backupService.jdbcUrl(), true);
        Ligacao direta = analisarLigacao(backupService.directUrl(), false);
        List<Map<String, Object>> checks = new ArrayList<>();

        if (app == null || app.host() == null) {
            checks.add(check("db-url", "Ligação da aplicação (DATABASE_URL)", FALHA, "Não está definida.",
                    "Defina DATABASE_URL com o pooler de TRANSAÇÃO do Supabase (porta 6543)."));
        } else if (app.host().matches("^db\\..*\\.supabase\\.co$")) {
            checks.add(check("db-url", "Ligação da aplicação (DATABASE_URL)", FALHA,
                    "Usa o host direto " + app.host() + ", que só resolve em IPv6.",
                    "Troque pelo host do pooler (aws-0-<região>.pooler.supabase.com), porta 6543, com prepareThreshold=0&sslmode=require."));
        } else if (app.porta() == null || app.porta() != 6543 || !app.pgbouncer()) {
            checks.add(check("db-url", "Ligação da aplicação (DATABASE_URL)", AVISO,
                    "Ligada a " + app.host() + ":" + app.porta() + (app.pgbouncer() ? " com pgbouncer" : " sem prepareThreshold=0") + ".",
                    "Em produção use o pooler de transação: porta 6543 e ?prepareThreshold=0&sslmode=require. Sem ele, o número de ligações esgota-se com o tráfego."));
        } else {
            checks.add(check("db-url", "Ligação da aplicação (DATABASE_URL)", OK, "Pooler de transação em " + app.host() + ":6543."));
        }

        if (direta == null || direta.host() == null) {
            checks.add(check("db-direct", "Ligação direta (DIRECT_URL)", FALHA, "Não está definida.",
                    "Defina DIRECT_URL com o pooler de SESSÃO (mesmo host, porta 5432). As migrações e a cópia de segurança precisam dela — o pooler de transação não serve para nenhuma das duas."));
        } else if (direta.porta() == null || direta.porta() != 5432) {
            checks.add(check("db-direct", "Ligação direta (DIRECT_URL)", FALHA, "Aponta para a porta " + direta.porta() + ".",
                    "A DIRECT_URL tem de ser o pooler de SESSÃO, na porta 5432. Na 6543 o pg_dump falha a meio e as migrações não aplicam."));
        } else {
            String problema = testarLigacaoDireta(app, direta);
            checks.add(problema != null
                    ? check("db-direct", "Ligação direta (DIRECT_URL)", FALHA,
                    "Aponta para " + direta.host() + ":5432, mas NÃO liga: " + problema + ".",
                    "As migrações e a cópia de segurança usam esta ligação, e mais nada — a plataforma "
                            + "continua a funcionar sem ela, o que faz com que a avaria só se note no deploy seguinte "
                            + "ou na noite em que a cópia devia correr. Corrija e reinicie o serviço.")
                    : check("db-direct", "Ligação direta (DIRECT_URL)", OK, "Pooler de sessão em " + direta.host() + ":5432 — ligação confirmada."));
        }
        return checks;
    }

    /**
     * A DIRECT_URL LIGA mesmo? Ler o texto do URL não chega: depois de uma
     * rotação de senha, quem só atualiza a DATABASE_URL vê a plataforma a
     * funcionar e a DIRECT_URL fica para trás com a senha antiga. Por isso
     * abre-se mesmo uma ligação. Devolve null se ligar, ou a explicação.
     */
    private String testarLigacaoDireta(Ligacao app, Ligacao direta) {
        // Se for o mesmo host:porta da aplicação, já sabemos que liga: o processo está de pé.
        if (app != null && app.host() != null && app.host().equals(direta.host()) && app.porta() != null && app.porta().equals(direta.porta())) {
            return null;
        }
        try {
            URI uri = URI.create(backupService.directUrl().replaceFirst("^postgres(ql)?://", "postgresql://"));
            String[] userInfo = uri.getUserInfo() == null ? new String[0] : uri.getUserInfo().split(":", 2);
            Properties props = new Properties();
            if (userInfo.length > 0) props.setProperty("user", java.net.URLDecoder.decode(userInfo[0], "UTF-8"));
            if (userInfo.length > 1) props.setProperty("password", java.net.URLDecoder.decode(userInfo[1], "UTF-8"));
            if (uri.getQuery() != null && uri.getQuery().contains("sslmode=require")) props.setProperty("sslmode", "require");
            props.setProperty("connectTimeout", "8");
            props.setProperty("loginTimeout", "8");
            String jdbc = "jdbc:postgresql://" + uri.getHost() + ":" + (uri.getPort() > 0 ? uri.getPort() : 5432) + uri.getPath();
            DriverManager.setLoginTimeout(8);
            try (Connection c = DriverManager.getConnection(jdbc, props); var st = c.createStatement()) {
                st.execute("SELECT 1");
            }
            return null;
        } catch (Exception err) {
            return explicarLigacao(err);
        }
    }

    // O erro cru do Postgres/Supabase não diz o que fazer. Estes três cobrem quase tudo.
    static String explicarLigacao(Throwable err) {
        String m = err.getMessage() == null ? "" : err.getMessage();
        String estado = err instanceof SQLException sql ? String.valueOf(sql.getSQLState()) : "";
        if ("28P01".equals(estado) || "28000".equals(estado) || m.matches("(?is).*(authentication failed|autentica|credentials .* not valid|senha).*")) {
            return "a senha foi RECUSADA — depois de rodar a senha do Supabase é preciso atualizar as DUAS "
                    + "variáveis; esta ficou com a antiga";
        }
        if (m.matches("(?is).*Tenant or user not found.*")) {
            return "o utilizador não foi reconhecido pelo pooler — confirme o formato postgres.<ref> no URL";
        }
        if (m.matches("(?is).*(UnknownHost|timed out|timeout|Connection refused|não respondeu|refused).*")) {
            return "o servidor não respondeu (" + m.substring(0, Math.min(80, m.length())) + ") — confirme o host e a porta";
        }
        return m.substring(0, Math.min(160, m.length()));
    }

    // --- Armazenamento ----------------------------------------------------------

    record Risco(long comprovativos, long documentos, long apolices, long fichas) {
        long total() {
            return comprovativos + documentos + apolices + fichas;
        }
    }

    /** Quantos ficheiros já estão no disco do contentor — ou seja, quantos se perdem no próximo reinício. */
    private Risco ficheirosEmRisco() {
        try {
            long comprovativos = paymentRepository.countByProofUrlStartingWith(LOCAL)
                    + planoCobrancaRepository.countByComprovativoUrlStartingWith(LOCAL);
            return new Risco(comprovativos, companyDocumentRepository.countByFileUrlStartingWith(LOCAL),
                    policyRepository.countByDocumentUrlStartingWith(LOCAL), productDocumentRepository.countByFileUrlStartingWith(LOCAL));
        } catch (Exception e) {
            // Uma contagem que falha não pode calar o aviso — o aviso é o que importa.
            return null;
        }
    }

    // Ordenado por gravidade do que se perde, não por número.
    private static String descreverRisco(Risco r) {
        if (r == null || r.total() == 0) return "";
        List<String> partes = new ArrayList<>();
        if (r.comprovativos() > 0) partes.add(r.comprovativos() + " comprovativo(s) de pagamento");
        if (r.documentos() > 0) partes.add(r.documentos() + " documento(s) de credenciamento");
        if (r.apolices() > 0) partes.add(r.apolices() + " apólice(s)");
        if (r.fichas() > 0) partes.add(r.fichas() + " ficha(s) técnica(s)");
        return " NESTE MOMENTO estão em risco: " + String.join(", ", partes) + ".";
    }

    private List<Map<String, Object>> verArmazenamento() {
        List<String> emFalta = storageService.emFalta();
        String configurado = storageService.providerConfigurado();
        if (!"s3".equals(configurado)) {
            Risco risco = ficheirosEmRisco();
            return List.of(check("storage", "Armazenamento de ficheiros", FALHA,
                    "A guardar no disco do contentor, que é apagado a cada deploy e a cada arranque depois de suspensão." + descreverRisco(risco),
                    "Defina STORAGE_PROVIDER=s3 e as credenciais do Supabase Storage (receita no DEPLOY.md). "
                            + "O que se perde não é decorativo: os COMPROVATIVOS DE TRANSFERÊNCIA são a prova inteira que sustenta "
                            + "o \"pagamento garantido\", e a certidão comercial, o alvará e a licença ANPG são o que justificou "
                            + "aprovar cada empresa. Quando desaparecem, a base continua a apontar para o URL e o pedido devolve "
                            + "404 sem registar erro nenhum — só se descobre no dia em que há uma disputa."));
        }
        if (!emFalta.isEmpty()) {
            boolean soOSecret = emFalta.size() == 1 && "STORAGE_SECRET_KEY".equals(emFalta.get(0));
            return List.of(check("storage", "Armazenamento de ficheiros", FALHA, "S3 ativo mas faltam: " + String.join(", ", emFalta) + ".",
                    (soOSecret
                            ? "O Supabase mostra a chave secreta UMA única vez, quando a cria — se fechou o painel, não a recupera. "
                            + "Em Project Settings → Storage → S3 access keys crie uma nova e atualize as DUAS variáveis "
                            + "(STORAGE_ACCESS_KEY e STORAGE_SECRET_KEY), depois apague a antiga. "
                            : "Preencha essas variáveis (Supabase → Project Settings → Storage → S3 access keys). ")
                            + "Uma variável criada mas deixada EM BRANCO conta como ausente. Reinicie o serviço depois de guardar — "
                            + "enquanto faltarem, os ficheiros vão para o disco do contentor e desaparecem no reinício seguinte."));
        }
        Risco risco = ficheirosEmRisco();
        if (risco != null && risco.total() > 0) {
            return List.of(check("storage", "Armazenamento de ficheiros", AVISO,
                    "S3 ativo no bucket \"" + storageService.bucket() + "\", mas há ficheiros carregados ANTES desta configuração "
                            + "que ainda apontam para o disco do contentor (já apagado)." + descreverRisco(risco),
                    "Estes ficheiros não têm forma de recuperação — o disco onde estavam já não existe. Peça a quem os "
                            + "carregou (comprovativo, documento de credenciamento, apólice, ficha técnica) para os enviar de novo; a "
                            + "partir daí ficam no S3 e sobrevivem a qualquer deploy."));
        }
        return List.of(check("storage", "Armazenamento de ficheiros", OK, "S3 ativo no bucket \"" + storageService.bucket() + "\"."));
    }

    // --- Cópias de segurança ----------------------------------------------------

    private static String temPgDump() {
        try {
            Process p = new ProcessBuilder("pg_dump", "--version").redirectErrorStream(true).start();
            String saida = new String(p.getInputStream().readAllBytes()).trim();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? saida : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String versaoDoServidor() {
        try {
            return jdbcTemplate.queryForObject("SHOW server_version", String.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer versaoMaior(String versao, String padrao) {
        if (versao == null) return null;
        Matcher m = Pattern.compile(padrao).matcher(versao);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private List<Map<String, Object>> verCopias() {
        String expressao = backupService.cron();
        List<Map<String, Object>> checks = new ArrayList<>();

        if (expressao == null || expressao.isBlank()) {
            checks.add(check("backup-cron", "Cópia de segurança automática", FALHA,
                    "Não está agendada — a variável BACKUP_CRON não chegou a este processo.",
                    "Se ainda não a definiu: BACKUP_CRON=0 3 * * * (cinco campos, sem aspas) dá uma cópia "
                            + "diária às 03:00 UTC. Se JÁ a definiu no Render e continua a ver isto, a variável é lida "
                            + "uma única vez no arranque: guardar no painel não basta, o serviço tem de reiniciar "
                            + "(Manual Deploy → Deploy latest commit, ou Restart service). Confirme também que ficou no "
                            + "serviço certo e que o nome não tem espaços nem letras minúsculas."));
        } else if (!BackupService.cronValido(expressao)) {
            boolean temAspas = expressao.matches(".*[\"'].*");
            boolean temTravessao = expressao.matches(".*[–—∗].*");
            checks.add(check("backup-cron", "Cópia de segurança automática", FALHA,
                    "A expressão \"" + expressao + "\" não é válida — a cópia automática não corre.",
                    (temAspas
                            ? "O valor tem ASPAS. O Render guarda-as como parte do valor e a expressão deixa de ser válida — "
                            + "escreva 0 3 * * * sem aspas nenhumas. "
                            : temTravessao
                            ? "O valor tem caracteres que não são asteriscos simples (provavelmente autocorreção ao copiar). "
                            + "Reescreva à mão: 0 3 * * * . "
                            : "")
                            + "Formato: cinco campos separados por espaços — minuto hora dia-do-mês mês dia-da-semana. "
                            + "Não são aceites @daily nem o caractere ? — o node-cron rejeita ambos. "
                            + "Depois de corrigir, reinicie o serviço: a expressão só é lida no arranque."));
        } else {
            checks.add(check("backup-cron", "Cópia de segurança automática", OK,
                    "Agendada: " + expressao + " (UTC), com recuperação automática se falhar a janela."));
        }

        String bucket = backupService.backupBucket();
        if (bucket == null || bucket.isBlank()) {
            checks.add(check("backup-bucket", "Bucket das cópias (STORAGE_BACKUP_BUCKET)", FALHA, "Não está definido.",
                    "Crie um bucket PRIVADO só para cópias e indique-o aqui. As cópias não podem ir para o bucket das imagens: esse é público, e um dump da base tem hashes de senha, os dados de todas as empresas e o histórico financeiro."));
        } else if (bucket.equals(backupService.bucket())) {
            checks.add(check("backup-bucket", "Bucket das cópias (STORAGE_BACKUP_BUCKET)", FALHA, "É o mesmo bucket das imagens, que é público.",
                    "Crie um bucket separado e PRIVADO. Neste estado a cópia automática recusa-se a correr — de propósito."));
        } else {
            checks.add(check("backup-bucket", "Bucket das cópias (STORAGE_BACKUP_BUCKET)", OK, "Bucket separado \"" + bucket + "\"."));
        }

        String versao = temPgDump();
        if (versao == null) {
            checks.add(check("pg-dump", "Ferramenta de cópia (pg_dump)", FALHA, "Não está instalada nesta imagem.",
                    "A imagem precisa do pacote postgresql-client. Sem ele a cópia falha todas as noites e a aplicação continua a dar sinal de estar tudo bem."));
        } else {
            // O pg_dump recusa-se a copiar um servidor MAIS RECENTE do que ele.
            Integer clienteMaior = versaoMaior(versao, "(\\d+)\\.");
            String servidor = versaoDoServidor();
            Integer servidorMaior = versaoMaior(servidor, "^(\\d+)");
            if (servidorMaior != null && clienteMaior != null && clienteMaior < servidorMaior) {
                checks.add(check("pg-dump", "Ferramenta de cópia (pg_dump)", FALHA,
                        "Cliente " + clienteMaior + " contra servidor PostgreSQL " + servidor + " — o pg_dump recusa-se a copiar um servidor mais recente do que ele.",
                        "A imagem precisa do cliente " + servidorMaior + " ou superior (no Dockerfile, postgresql" + servidorMaior + "-client em vez de postgresql-client)."));
            } else {
                checks.add(check("pg-dump", "Ferramenta de cópia (pg_dump)", OK, servidor != null ? versao + " — servidor PostgreSQL " + servidor : versao));
            }
        }

        // A falha mais traiçoeira: estava tudo configurado e a cópia deixou de correr sem ninguém reparar.
        AuditLog ultima = auditLogRepository.findFirstByActionOrderByCreatedAtDesc("COPIA_SEGURANCA_CONCLUIDA").orElse(null);
        if (ultima == null) {
            checks.add(check("backup-ultima", "Última cópia com sucesso", expressao != null && !expressao.isBlank() ? AVISO : FALHA,
                    "Ainda não há nenhuma cópia registada.",
                    "Use o botão \"Fazer cópia agora\" para confirmar que tudo funciona antes de confiar no agendamento. Uma cópia que nunca correu não é uma cópia."));
        } else {
            double horas = (System.currentTimeMillis() - ultima.getCreatedAt().toEpochMilli()) / 36e5;
            String megabytes = megabytesDe(ultima.getDetail());
            checks.add(check("backup-ultima", "Última cópia com sucesso", horas > 48 ? FALHA : OK,
                    MINUTO_UTC.format(ultima.getCreatedAt()) + " UTC" + (megabytes != null ? " — " + megabytes + " MB" : ""),
                    horas > 48
                            ? "Já passaram " + (long) Math.floor(horas) + " horas, e a recuperação automática também não a repôs. "
                            + "Verifique o registo do serviço: a cópia está a falhar, não a ser adiada."
                            : null));

            // A escrita não prova que a cópia se lê.
            AuditLog verificada = auditLogRepository.findFirstByActionOrderByCreatedAtDesc("COPIA_SEGURANCA_VERIFICADA").orElse(null);
            checks.add(check("backup-legivel", "A cópia foi lida de volta", verificada != null ? OK : AVISO,
                    verificada != null
                            ? "Confirmada a " + MINUTO_UTC.format(verificada.getCreatedAt()) + " UTC."
                            : "Nunca foi confirmado que a cópia se lê — só que se escreve.",
                    verificada != null ? null
                            : "Use \"Verificar a última cópia\": vai buscá-la ao bucket, descomprime-a e confirma que traz "
                            + "a base toda. Um objeto truncado ou um gzip corrompido são indistinguíveis de uma cópia "
                            + "boa até alguém tentar lê-los."));
        }
        return checks;
    }

    private String megabytesDe(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) return null;
        try {
            JsonNode n = objectMapper.readTree(detailJson).get("megabytes");
            return n == null || n.isNull() || (n.isNumber() && n.doubleValue() == 0) ? null : n.asText();
        } catch (Exception e) {
            return null;
        }
    }

    // --- Email ------------------------------------------------------------------

    private List<Map<String, Object>> verEmail() {
        List<Map<String, Object>> checks = new ArrayList<>();
        if (emailDispatchService.apenasLog()) {
            checks.add(check("email", "Envio de email", FALHA, "EMAIL_PROVIDER=console — nada é enviado, tudo fica no registo.",
                    "Defina EMAIL_PROVIDER=brevo e BREVO_API_KEY. Neste estado os convites, a recuperação de senha e os avisos de fatura nunca chegam a ninguém, e não aparece erro nenhum."));
        } else if (!emailDispatchService.emFalta().isEmpty()) {
            checks.add(check("email", "Envio de email", FALHA,
                    "Provider \"" + emailDispatchService.provider() + "\" configurado mas faltam: " + String.join(", ", emailDispatchService.emFalta()) + ".",
                    "Preencha essas variáveis e reinicie o serviço."));
        } else {
            checks.add(check("email", "Envio de email", OK, "Provider \"" + emailDispatchService.provider() + "\" configurado."));
        }

        String remetente = emailDispatchService.from() == null ? "" : emailDispatchService.from();
        checks.add(!remetente.isBlank()
                ? check("email-from", "Remetente (EMAIL_FROM)", OK, remetente,
                "Confirme que este endereço está VERIFICADO na conta Brevo — se não estiver, o envio é recusado.")
                : check("email-from", "Remetente (EMAIL_FROM)", FALHA, "Não está definido.",
                "Defina EMAIL_FROM com um remetente verificado na conta Brevo."));

        // O APP_URL é a única entrada da allow-list de CORS e o valor que manda quando houver domínio próprio.
        if (appUrl.matches(".*(localhost|127\\.0\\.0\\.1).*")) {
            checks.add(check("app-url", "Endereço público (APP_URL)", AVISO, appUrl,
                    "Ainda aponta para localhost. Os links dos convites saem certos mesmo assim, porque são "
                            + "construídos a partir do endereço real do pedido — mas o APP_URL é a única entrada da "
                            + "allow-list de CORS e é o valor que manda assim que ligar um domínio próprio. Defina-o com "
                            + "o endereço real do serviço."));
        } else {
            checks.add(check("app-url", "Endereço público (APP_URL)", OK, appUrl));
        }
        return checks;
    }

    // --- 2FA obrigatória --------------------------------------------------------

    private List<Map<String, Object>> verMfa() {
        List<Map<String, Object>> checks = new ArrayList<>();
        List<PersonaRole> perfis = mfaPolicyService.rolesObrigados();
        String perfisTexto = String.join(" e ", perfis.stream().map(Enum::name).toList());
        String perfisOu = String.join(" ou ", perfis.stream().map(Enum::name).toList());
        Instant prazo = mfaPolicyService.mfaEnforceFrom();

        Long emFalta;
        try {
            emFalta = userRepository.countByRoleInAndActiveTrueAndTotpEnabledAtIsNull(perfis);
        } catch (Exception e) {
            emFalta = null;
        }

        if (mfaPolicyService.mfaEnforceFromInvalido() != null) {
            checks.add(check("mfa-prazo", "2FA obrigatória (MFA_ENFORCE_FROM)", FALHA,
                    "O valor \"" + mfaPolicyService.mfaEnforceFromInvalido() + "\" não é uma data — está a ser ignorado, e a 2FA NÃO é exigida a ninguém.",
                    "Escreva a data no formato ISO, sem aspas: 2026-09-15T00:00:00Z (ou só 2026-09-15). "
                            + "Formatos como 15/09/2026 não são lidos. Depois de corrigir, reinicie o serviço."));
        } else if (prazo == null) {
            checks.add(check("mfa-prazo", "2FA obrigatória (MFA_ENFORCE_FROM)", AVISO, "Sem data definida — a 2FA é só um aviso e nunca é exigida.",
                    "Defina MFA_ENFORCE_FROM com uma data futura no formato 2026-09-15T00:00:00Z (sem aspas), para dar prazo a "
                            + perfisTexto + " configurarem a 2FA antes de passar a ser obrigatória."));
        } else {
            boolean passou = !Instant.now().isBefore(prazo);
            String data = prazo.toString().substring(0, 10);
            boolean aviso = passou && emFalta != null && emFalta > 0;
            checks.add(check("mfa-prazo", "2FA obrigatória (MFA_ENFORCE_FROM)", aviso ? AVISO : OK,
                    passou ? "Em vigor desde " + data + "." : "Entra em vigor a " + data + ".",
                    aviso ? emFalta + " conta(s) ainda sem 2FA: a sessão delas só dá acesso ao ecrã de ativação. Avise essas pessoas." : null));
        }

        checks.add(check("mfa-contas", "Contas com poder sem 2FA",
                emFalta == null ? AVISO : emFalta == 0 ? OK : AVISO,
                emFalta == null ? "Não foi possível contar."
                        : emFalta == 0 ? "Nenhuma. Todos os perfis " + perfisTexto + " têm 2FA ativa."
                        : emFalta + " de perfil " + perfisOu + ".",
                emFalta != null && emFalta > 0 ? "Cada pessoa ativa a sua em Configurações → Segurança. Ninguém pode fazê-lo por ela — é esse o ponto." : null));
        return checks;
    }

    // --- Contas bloqueadas ------------------------------------------------------

    private List<Map<String, Object>> verContasBloqueadas() {
        List<User> contas;
        try {
            contas = userRepository.findByBloqueadoAteAfterOrderByEmailAsc(Instant.now());
        } catch (Exception err) {
            return List.of(check("bloqueios", "Contas bloqueadas por tentativas falhadas", AVISO, "Não foi possível contar: " + err.getMessage()));
        }
        if (contas.isEmpty()) {
            return List.of(check("bloqueios", "Contas bloqueadas por tentativas falhadas", OK, "Nenhuma neste momento."));
        }
        boolean varias = contas.size() > 2;
        return List.of(check("bloqueios", "Contas bloqueadas por tentativas falhadas", varias ? AVISO : OK,
                contas.size() + " conta(s): " + String.join(", ", contas.stream().map(User::getEmail).toList()) + ".",
                varias
                        ? "Várias contas bloqueadas ao mesmo tempo não é distração — é alguém a varrer a plataforma. "
                        + "Os titulares já foram avisados por email; confirme com eles e considere antecipar o prazo da 2FA."
                        : null));
    }

    // --- Segredos ---------------------------------------------------------------

    private List<Map<String, Object>> verSegredos() {
        String s = jwtSecret;
        if (s.isBlank() || "CHANGE_ME".equals(s)) {
            return List.of(check("jwt", "Chave de assinatura das sessões (JWT_SECRET)", FALHA, "Em falta ou por preencher.",
                    "Defina um valor aleatório longo. Com uma chave conhecida, qualquer pessoa forja uma sessão de administrador."));
        }
        if (s.length() < 32) {
            return List.of(check("jwt", "Chave de assinatura das sessões (JWT_SECRET)", AVISO, "Tem " + s.length() + " caracteres.",
                    "Use pelo menos 32 caracteres aleatórios. Trocá-la termina todas as sessões abertas — o que é aceitável, e por vezes desejável."));
        }
        return List.of(check("jwt", "Chave de assinatura das sessões (JWT_SECRET)", OK, "Definida (" + s.length() + " caracteres)."));
    }

    // --- Cobranças de subscrição -------------------------------------------------

    /** O IBAN não é segredo (é para ser dado a quem paga) — é o único valor mostrado por inteiro, para se poder CONFERIR. */
    private List<Map<String, Object>> verCobrancas() {
        AssinaturaService.DadosBancarios b = assinaturaService.dadosBancarios();
        if (!b.configurado()) {
            return List.of(check("iban", "Dados bancários para as subscrições", FALHA, "KIXIMA_BANCO_IBAN não está definido.",
                    "Defina KIXIMA_BANCO_IBAN (e, se quiser, KIXIMA_BANCO_TITULAR, KIXIMA_BANCO_NOME, "
                            + "KIXIMA_BANCO_SWIFT e KIXIMA_BANCO_MOEDA). Sem o IBAN, quem pede um plano lê \"transfira o valor\" "
                            + "e não tem para onde."));
        }
        List<String> faltam = new ArrayList<>();
        if (b.titular() == null || b.titular().isBlank()) faltam.add("titular");
        if (b.banco() == null || b.banco().isBlank()) faltam.add("banco");
        if (b.swift() == null || b.swift().isBlank()) faltam.add("swift");
        return List.of(check("iban", "Dados bancários para as subscrições", faltam.isEmpty() ? OK : AVISO,
                "IBAN " + b.iban() + " · " + b.moeda() + (b.banco() != null && !b.banco().isBlank() ? " · " + b.banco() : "")
                        + (b.titular() != null && !b.titular().isBlank() ? " · " + b.titular() : "") + ". "
                        + "Confira que é a conta certa — é este o número que as empresas vão usar.",
                faltam.isEmpty() ? null : "Por preencher: " + String.join(", ", faltam) + ". Uma transferência internacional costuma exigir o SWIFT e o titular."));
    }

    // --- Faturação certificada (AGT) ----------------------------------------------

    private List<Map<String, Object>> verFaturacao() {
        List<Map<String, Object>> checks = new ArrayList<>();
        long comSerie = companyRepository.countByTypeAndSerieFiscalIsNotNull(CompanyType.FORNECEDOR);
        long totalFornecedores = companyRepository.countByType(CompanyType.FORNECEDOR);

        if (comSerie == 0) {
            checks.add(check("agt-serie", "Série de faturação certificada (por fornecedor)", AVISO,
                    "Nenhuma das " + totalFornecedores + " empresas fornecedoras tem série declarada — todas emitem sem numeração certificada.",
                    "Declare a série de cada fornecedor em Empresas → [fornecedor] → Série fiscal, SÓ depois de a AGT lha atribuir. Ligá-la antes disso emitiria documentos numa série que não existe, e a numeração não se corrige para trás."));
        } else {
            checks.add(check("agt-serie", "Série de faturação certificada (por fornecedor)", OK,
                    comSerie + " de " + totalFornecedores + " empresas fornecedoras com série declarada.",
                    "Confirme em Faturação → Integridade que nenhuma dessas cadeias tem buracos."));
        }

        checks.add(!nifKixima.isBlank()
                ? check("agt-nif", "NIF da KIXIMA como fabricante do software (KIXIMA_NIF)", OK, nifKixima)
                : check("agt-nif", "NIF da KIXIMA como fabricante do software (KIXIMA_NIF)", AVISO, "Não está definido.",
                "O SAF-T sai com o campo ProductCompanyTaxID por preencher. É preferível a um número inventado, mas a AGT recusa o ficheiro assim."));

        checks.add(!certificadoAgt.isBlank()
                ? check("agt-certificado", "Certificado do programa (AGT)", OK, certificadoAgt)
                : check("agt-certificado", "Certificado do programa (AGT)", AVISO, "Ainda não atribuído.",
                "Sai do processo de certificação junto da AGT. Fica vazio até existir — um número de certificado inventado num ficheiro fiscal é uma declaração falsa."));

        Map<String, Object> assinatura = agtSigningService.estado();
        @SuppressWarnings("unchecked")
        Map<String, Object> chave = (Map<String, Object>) assinatura.get("chavePrivada");
        @SuppressWarnings("unchecked")
        List<String> emFalta = (List<String>) assinatura.get("emFalta");
        boolean disponivel = Boolean.TRUE.equals(assinatura.get("disponivel"));
        checks.add(disponivel
                ? check("agt-assinatura", "Assinatura JWS do payload de submissão (AGT)", OK, "Configurada. Chave privada: " + chave.get("fonte") + ".")
                : check("agt-assinatura", "Assinatura JWS do payload de submissão (AGT)", AVISO,
                assinatura.get("nota") + " Chave privada: " + chave.get("fonte") + ".",
                "Requer certificação e chave privada reais da AGT. Em falta: " + String.join(", ", emFalta) + ". Sem isto o payload não é assinado — nunca uma assinatura simulada."));
        return checks;
    }

    // --- Canais de pagamento ------------------------------------------------------

    private List<Map<String, Object>> verCanaisDePagamento() {
        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(check("pag-manual", "Transferência com comprovativo", OK, "Sempre disponível.",
                "É a alternativa que fica de pé quando um canal automático falha — não a desligue."));

        boolean iban = assinaturaService.dadosBancarios().configurado();
        checks.add(iban
                ? check("pag-referencia", "Referência bancária", OK, "Cada fatura recebe uma referência única, conciliada pelo extrato.")
                : check("pag-referencia", "Referência bancária (KIXIMA_BANCO_IBAN)", FALHA, "O IBAN não está definido.",
                "Sem ele a plataforma gera a referência mas não tem para onde dizer que se transfira — o comprador vê uma referência e nenhuma conta."));

        Map<String, Object> estados = canaisPagamentoService.estados();
        for (CanalCobranca canal : CanaisPagamentoService.CANAIS_GATEWAY) {
            @SuppressWarnings("unchecked")
            Map<String, Object> e = (Map<String, Object>) estados.get(canal.name());
            String nome = NOME_CANAL.getOrDefault(canal.name(), canal.name());
            boolean disponivel = e != null && Boolean.TRUE.equals(e.get("disponivel"));
            @SuppressWarnings("unchecked")
            List<String> emFalta = e == null ? List.of() : (List<String>) e.get("emFalta");
            checks.add(disponivel
                    ? check("pag-" + canal.name().toLowerCase(), nome, OK, "Configurado.")
                    : check("pag-" + canal.name().toLowerCase(), nome, AVISO, e == null ? "Canal desconhecido." : String.valueOf(e.get("nota")),
                    "Requer contrato e credenciais do " + nome + ". Em falta: " + String.join(", ", emFalta) + ". Sem isto o canal recusa-se a funcionar em vez de simular — que é o comportamento certo."));
        }
        return checks;
    }

    // --- Tudo junto ------------------------------------------------------------------

    private static Map<String, Object> grupo(String nome, List<Map<String, Object>> checks) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("grupo", nome);
        m.put("checks", checks);
        return m;
    }

    private String ambiente() {
        String[] perfis = environment.getActiveProfiles();
        return perfis.length == 0 ? "development" : perfis[0];
    }

    /** Estado de prontidão do ambiente onde este processo corre. Nunca devolve o valor de nenhum segredo. */
    @Transactional(readOnly = true)
    public Map<String, Object> verificar() {
        List<Map<String, Object>> grupos = List.of(
                grupo("Base de dados", verBaseDeDados()),
                grupo("Armazenamento", verArmazenamento()),
                grupo("Cópias de segurança", verCopias()),
                grupo("Email", verEmail()),
                grupo("Autenticação de dois fatores", verMfa()),
                grupo("Segredos", verSegredos()),
                grupo("Contas sob ataque", verContasBloqueadas()),
                grupo("Cobranças de subscrição", verCobrancas()),
                grupo("Faturação certificada (AGT)", verFaturacao()),
                grupo("Canais de pagamento", verCanaisDePagamento()));

        long ok = 0;
        long avisos = 0;
        long falhas = 0;
        long total = 0;
        for (Map<String, Object> g : grupos) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> checks = (List<Map<String, Object>>) g.get("checks");
            for (Map<String, Object> c : checks) {
                total++;
                switch (String.valueOf(c.get("estado"))) {
                    case OK -> ok++;
                    case AVISO -> avisos++;
                    default -> falhas++;
                }
            }
        }
        Map<String, Object> resumo = new LinkedHashMap<>();
        resumo.put("total", total);
        resumo.put("ok", ok);
        resumo.put("avisos", avisos);
        resumo.put("falhas", falhas);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ambiente", ambiente());
        out.put("verificadoEm", Instant.now());
        out.put("resumo", resumo);
        out.put("grupos", grupos);
        if (log.isDebugEnabled()) log.debug("Prontidão: {} ok, {} avisos, {} falhas", ok, avisos, falhas);
        return out;
    }
}
