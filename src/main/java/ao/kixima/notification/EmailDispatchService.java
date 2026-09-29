package ao.kixima.notification;

import ao.kixima.common.error.BadGatewayException;
import ao.kixima.common.error.ValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Espelha o essencial de backend/src/services/notificationService.js
 * (dispatchEmail/sendViaBrevoApi/enviarEmailDireto) e a deteção de
 * configuração em falta de config/env.js — envio de email plugável por
 * {@code EMAIL_PROVIDER}, com as MESMAS variáveis de ambiente do Node:
 * <ul>
 *   <li>'console' só regista no log (comportamento por omissão, sem nenhuma
 *   credencial);</li>
 *   <li>'brevo'/'brevo-api' chama a API HTTP do Brevo (porta 443) com
 *   {@code BREVO_API_KEY};</li>
 *   <li>'smtp' usa JavaMail (o equivalente ao nodemailer) com
 *   {@code SMTP_HOST}/{@code SMTP_PORT}/{@code SMTP_USER}/{@code SMTP_PASSWORD}
 *   — 465 = TLS direto, qualquer outra porta = STARTTLS obrigatório, os
 *   mesmos tempos limite curtos do Node para falhar depressa sem bloquear o
 *   pedido.</li>
 * </ul>
 * Um provider desconhecido cai no mesmo aviso "EMAIL_PROVIDER desconhecido"
 * do Node e nunca bloqueia quem chama (mesmo princípio de "uma falha de envio
 * é registada e o pedido segue").
 */
@Service
public class EmailDispatchService {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatchService.class);
    private static final Pattern FROM_PATTERN = Pattern.compile("^\\s*(.*?)\\s*<\\s*([^>]+)\\s*>\\s*$");

    private final String provider;
    private final String from;
    private final String brevoApiKey;
    private final String smtpHost;
    private final int smtpPort;
    private final String smtpUser;
    private final String smtpPassword;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public EmailDispatchService(@Value("${kixima.email.provider:console}") String provider,
                                 @Value("${kixima.email.from:notificacoes@kixima.co.ao}") String from,
                                 @Value("${kixima.email.brevo-api-key:}") String brevoApiKey,
                                 @Value("${kixima.email.smtp.host:}") String smtpHost,
                                 @Value("${kixima.email.smtp.port:}") String smtpPort,
                                 @Value("${kixima.email.smtp.user:}") String smtpUser,
                                 @Value("${kixima.email.smtp.password:}") String smtpPassword,
                                 ObjectMapper objectMapper) {
        this.provider = provider;
        this.from = from;
        this.brevoApiKey = brevoApiKey;
        this.smtpHost = smtpHost;
        this.smtpPort = portaSmtp(smtpPort);
        this.smtpUser = smtpUser;
        this.smtpPassword = smtpPassword;
        this.objectMapper = objectMapper;
    }

    /** Espelha `Number(process.env.SMTP_PORT) || 587` — vazio ou inválido cai em 587. */
    private static int portaSmtp(String valor) {
        try {
            int p = Integer.parseInt(valor == null ? "" : valor.trim());
            return p > 0 ? p : 587;
        } catch (NumberFormatException e) {
            return 587;
        }
    }

    private record Remetente(String nome, String email) {
    }

    private Remetente parseFrom(String from) {
        Matcher m = FROM_PATTERN.matcher(from == null ? "" : from);
        if (m.matches()) {
            String nome = m.group(1);
            return new Remetente(nome == null || nome.isBlank() ? null : nome, m.group(2));
        }
        String limpo = from == null ? "" : from.trim();
        return new Remetente(null, limpo.isBlank() ? null : limpo);
    }

    private boolean ehBrevo() {
        return "brevo".equals(provider) || "brevo-api".equals(provider);
    }

    private void enviarViaBrevoApi(String to, String subject, String body, String html) throws IOException, InterruptedException {
        Remetente remetente = parseFrom(from);
        Map<String, Object> sender = new LinkedHashMap<>();
        if (remetente.nome() != null) sender.put("name", remetente.nome());
        sender.put("email", remetente.email());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sender", sender);
        payload.put("to", List.of(Map.of("email", to)));
        payload.put("subject", subject);
        payload.put("textContent", body);
        if (html != null) payload.put("htmlContent", html);

        String corpo;
        try {
            corpo = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Falha a serializar pedido Brevo.", e);
        }

        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.brevo.com/v3/smtp/email"))
                .timeout(Duration.ofSeconds(20))
                .header("api-key", brevoApiKey == null ? "" : brevoApiKey)
                .header("content-type", "application/json")
                .header("accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(corpo, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            String detalhe = resp.body() == null ? "" : resp.body().substring(0, Math.min(200, resp.body().length()));
            throw new IllegalStateException("Brevo API " + resp.statusCode() + ": " + detalhe);
        }
    }

    /**
     * Espelha `nodemailer.createTransport({...})` — criado a cada envio, tal
     * como no Node (que carrega o nodemailer de forma preguiçosa e monta o
     * transporte por chamada). 465 = TLS direto; 587/2525 = STARTTLS
     * obrigatório. Tempos limite curtos: falha rápido se o SMTP estiver
     * indisponível, não bloqueia o pedido.
     */
    private JavaMailSenderImpl transporteSmtp() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(smtpHost);
        sender.setPort(smtpPort);
        sender.setDefaultEncoding("UTF-8");
        boolean comAuth = smtpUser != null && !smtpUser.isBlank();
        if (comAuth) {
            sender.setUsername(smtpUser);
            sender.setPassword(smtpPassword);
        }
        Properties p = sender.getJavaMailProperties();
        p.put("mail.transport.protocol", "smtp");
        p.put("mail.smtp.auth", String.valueOf(comAuth));
        if (smtpPort == 465) {
            p.put("mail.smtp.ssl.enable", "true");
        } else {
            p.put("mail.smtp.starttls.enable", "true");
            p.put("mail.smtp.starttls.required", "true");
        }
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "15000");
        p.put("mail.smtp.writetimeout", "15000");
        return sender;
    }

    private void enviarViaSmtp(String to, String subject, String body, String html) throws Exception {
        JavaMailSenderImpl sender = transporteSmtp();
        MimeMessage msg = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(msg, html != null, "UTF-8");
        helper.setFrom(from);
        helper.setTo(to);
        helper.setSubject(subject);
        if (html != null) {
            helper.setText(body, html);
        } else {
            helper.setText(body);
        }
        sender.send(msg);
    }

    public String provider() {
        return provider;
    }

    public String from() {
        return from;
    }

    /** Espelha `config.email.apenasLog` — 'console' não é um provider a sério: escreve no log e segue. */
    public boolean apenasLog() {
        return "console".equals(provider);
    }

    /**
     * Espelha `config.email.missing` (EMAIL_REQUIRED em config/env.js) — nomes
     * das variáveis em falta para o provider configurado (nunca valores).
     * Um provider desconhecido não exige nada, tal como lá.
     */
    public List<String> emFalta() {
        Map<String, String> exigido = new LinkedHashMap<>();
        if (ehBrevo()) {
            exigido.put("BREVO_API_KEY", brevoApiKey);
        } else if ("smtp".equals(provider)) {
            exigido.put("SMTP_HOST", smtpHost);
            exigido.put("SMTP_USER", smtpUser);
            exigido.put("SMTP_PASSWORD", smtpPassword);
        }
        List<String> falta = new ArrayList<>();
        for (Map.Entry<String, String> e : exigido.entrySet()) {
            if (e.getValue() == null || e.getValue().trim().isEmpty()) falta.add(e.getKey());
        }
        return List.copyOf(falta);
    }

    /**
     * Espelha `!config.email.apenasLog && !config.email.missing.length` —
     * usado por alertaOperacionalService/mfaLembreteService para não tentar
     * um envio que se sabe à partida que não vai a lado nenhum (provider
     * 'console', ou um provider real sem as credenciais que precisa).
     */
    public boolean configurado() {
        return !apenasLog() && emFalta().isEmpty();
    }

    public record EnvioDireto(String provider, String para, String remetente) {
    }

    /**
     * Espelha notificationService.enviarEmailDireto — o ÚNICO caminho em que o
     * erro NÃO é engolido. Em todo o resto, uma falha de envio é registada e o
     * pedido segue: um convite não deve deixar de ser criado porque o servidor
     * de email não respondeu. Mas há dois casos em que engolir o erro é o pior
     * que se pode fazer: quem está a CONFIGURAR o email precisa de ver o que
     * correu mal (a mensagem crua do Brevo é a que diz o que corrigir); e o
     * código de verificação em dois passos É o acesso à conta — se não sai, a
     * pessoa tem de o saber na hora.
     *
     * Tal como no Node, qualquer provider que não seja console/brevo segue
     * pelo transporte SMTP (o Node não valida o nome do provider aqui).
     */
    public EnvioDireto enviarDireto(String to, String assunto, String corpo) {
        if (apenasLog()) {
            throw new ValidationException("EMAIL_PROVIDER=console — nada é enviado. Defina EMAIL_PROVIDER=brevo e BREVO_API_KEY.");
        }
        List<String> falta = emFalta();
        if (!falta.isEmpty()) {
            throw new ValidationException("Faltam variáveis de email: " + String.join(", ", falta) + ".");
        }
        if (ehBrevo()) {
            try {
                enviarViaBrevoApi(to, assunto, corpo, null);
            } catch (Exception e) {
                throw new BadGatewayException(e.getMessage() == null ? "Falha no envio de email." : e.getMessage());
            }
            return new EnvioDireto("brevo", to, from);
        }
        try {
            enviarViaSmtp(to, assunto, corpo, null);
        } catch (Exception e) {
            throw new BadGatewayException(e.getMessage() == null ? "Falha no envio de email." : e.getMessage());
        }
        return new EnvioDireto("smtp", to, from);
    }

    /** Email de teste da página de Prontidão — confirma a configuração de ponta a ponta. */
    public EnvioDireto enviarEmailDeTeste(String to) {
        return enviarDireto(to, "KIXIMA — teste de configuração de email",
                "Se está a ler isto, o envio de email da plataforma KIXIMA está a funcionar.\n\n"
                        + "Este email foi enviado a partir de Configurações e Suporte → Prontidão para produção.");
    }

    /** Nunca lança — uma falha de envio é registada e quem chama continua. */
    public void dispatch(String to, String subject, String body) {
        dispatch(to, subject, body, null);
    }

    /** Espelha dispatchEmail(to, subject, body, { html }) — o HTML é opcional (só a recuperação de senha o usa). */
    public void dispatch(String to, String subject, String body, String html) {
        if ("console".equals(provider) || to == null || to.isBlank()) {
            log.info("Email (modo console) para={} assunto={}", to, subject);
            return;
        }
        if (ehBrevo()) {
            try {
                enviarViaBrevoApi(to, subject, body, html);
                log.info("Email enviado (Brevo API) para={} assunto={}", to, subject);
            } catch (Exception err) {
                log.error("Falha no envio de email (Brevo API) para={} assunto={}: {}", to, subject, err.getMessage());
            }
            return;
        }
        if ("smtp".equals(provider)) {
            try {
                enviarViaSmtp(to, subject, body, html);
                log.info("Email enviado (SMTP) para={} assunto={}", to, subject);
            } catch (Exception err) {
                log.error("Falha no envio de email (SMTP) para={} assunto={}: {}", to, subject, err.getMessage());
            }
            return;
        }
        log.warn("Email não enviado — EMAIL_PROVIDER desconhecido provider={} para={} assunto={}", provider, to, subject);
    }
}
