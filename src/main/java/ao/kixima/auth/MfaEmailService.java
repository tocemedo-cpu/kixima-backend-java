package ao.kixima.auth;

import ao.kixima.common.error.BusinessRuleException;
import ao.kixima.notification.EmailDispatchService;
import ao.kixima.notification.EmailI18n;
import ao.kixima.user.User;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * Espelha backend/src/services/mfaEmailService.js — verificação em dois
 * passos por EMAIL: um código de 6 dígitos enviado para o endereço da pessoa.
 *
 * Porquê existir, ao lado do TOTP: a app de autenticação é mais segura (o
 * código nasce no telemóvel, sem rede, e não passa por lado nenhum), mas obriga
 * a instalar e configurar uma aplicação — e isso, na prática, faz com que a 2FA
 * não seja ativada de todo. Um segundo fator que ninguém usa protege zero
 * contas. O código por email é mais fraco do que o TOTP e continua a ser um
 * segundo fator a sério: quem roubar a senha não entra sem aceder também à
 * caixa de correio.
 *
 * O que NÃO é: uma segunda palavra-passe. Essa seria a mesma categoria de
 * segredo que a primeira — algo que a pessoa sabe — e quem obtivesse uma pela
 * via habitual (fuga, reutilização, phishing) obteria a outra do mesmo modo.
 *
 * Persistência: muta o {@link User} gerido pelo JPA — a transação de quem chama
 * (AuthService) persiste as alterações. Os pontos em que o Node escreve e A
 * SEGUIR falha (tentativa errada contada, código apagado depois de um envio
 * falhado) só sobrevivem porque os métodos chamadores declaram
 * {@code noRollbackFor} para essas excepções — ver AuthService.
 */
@Service
public class MfaEmailService {

    public static final int VALIDADE_MINUTOS = 10;
    public static final int TENTATIVAS_MAX = 5;
    public static final int INTERVALO_REENVIO_SEGUNDOS = 60;

    /** Mesmo custo do Node (`bcrypt.hash(codigo, 10)`) — o da senha é 12, este é um código de 10 minutos. */
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(10);
    private final SecureRandom random = new SecureRandom();
    private final EmailDispatchService emailDispatchService;

    public MfaEmailService(EmailDispatchService emailDispatchService) {
        this.emailDispatchService = emailDispatchService;
    }

    /** Espelha o objecto devolvido por enviarCodigo — `reaproveitado` só sai quando é true (no Node nem existe nos outros casos). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Envio(String enviadoPara, Instant expiraEm, int validadeMinutos, Boolean reaproveitado) {
    }

    /** 6 dígitos com aleatoriedade criptográfica — isto é uma credencial de acesso. */
    private String gerarCodigo() {
        return String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
    }

    /**
     * b***o@gmail.com — confirma à pessoa para onde foi sem expor o endereço
     * inteiro a quem esteja a olhar para o ecrã.
     */
    public String mascarar(String email) {
        String[] partes = (email == null ? "" : email).split("@", -1);
        if (partes.length < 2) return "";
        String nome = partes[0];
        String dominio = partes[1];
        String visivel = nome.length() <= 2
                ? (nome.isEmpty() ? "" : nome.substring(0, 1))
                : nome.charAt(0) + "*".repeat(Math.min(nome.length() - 2, 4)) + nome.charAt(nome.length() - 1);
        return visivel + "@" + dominio;
    }

    /**
     * O envio de email está mesmo a funcionar?
     *
     * Esta pergunta tem de ser feita ANTES de deixar alguém ativar a 2FA por email.
     * Com EMAIL_PROVIDER=console o envio é engolido pelo log: a pessoa ativaria a
     * 2FA, sairia da sessão, e nunca mais entraria — sem erro nenhum a explicar
     * porquê. É a forma mais fácil de trancar toda a gente fora da plataforma.
     */
    public String porqueNaoPodeUsarEmail() {
        if (emailDispatchService.apenasLog()) {
            return "O envio de email não está configurado neste servidor (EMAIL_PROVIDER=console), "
                    + "por isso o código nunca chegaria e a conta ficaria inacessível. "
                    + "Configure o email antes de ativar a verificação por email.";
        }
        if (!emailDispatchService.emFalta().isEmpty()) {
            return "O envio de email está incompleto — faltam: " + String.join(", ", emailDispatchService.emFalta()) + ". "
                    + "O código não chegaria e a conta ficaria inacessível.";
        }
        return null;
    }

    public Envio enviarCodigo(User user) {
        return enviarCodigo(user, "login", false);
    }

    /**
     * Gera um código, guarda-o em hash e envia-o.
     *
     * O erro do envio NÃO é engolido, ao contrário do resto da plataforma. Noutros
     * sítios faz sentido: um convite não deve deixar de ser criado porque o
     * servidor de email não respondeu. Aqui é o oposto — se o email não sai, a
     * pessoa fica à espera de um código que não existe, e tem de o saber já.
     *
     * @param motivo     'login' ou 'ativacao' (muda o assunto)
     * @param automatico true no envio feito pelo próprio login
     */
    public Envio enviarCodigo(User user, String motivo, boolean automatico) {
        String impedimento = porqueNaoPodeUsarEmail();
        if (impedimento != null) throw new BusinessRuleException(impedimento);

        Instant agora = Instant.now();
        boolean pendente = user.getMfaCodeHash() != null
                && user.getMfaCodeExpiraEm() != null && agora.isBefore(user.getMfaCodeExpiraEm());

        // Envio AUTOMÁTICO (o do login): se já há um código válido à espera, reaproveita-o
        // em vez de mandar outro.
        //
        // Aqui não pode haver erro nenhum. A primeira versão aplicava o travão dos 60
        // segundos também neste caminho, e o resultado era grave: quem acabasse de
        // ativar a 2FA e voltasse a entrar no minuto seguinte via o LOGIN falhar com
        // "aguarde 47 segundos" — sem forma de entrar. O travão existe para conter
        // pedidos repetidos de propósito, não para barrar quem está a autenticar-se.
        if (automatico && pendente) {
            return new Envio(mascarar(user.getEmail()), user.getMfaCodeExpiraEm(), VALIDADE_MINUTOS, true);
        }

        // Pedido EXPLÍCITO ("enviar outro código"): trava a repetição, para não se usar
        // a plataforma para inundar a caixa de correio de alguém. Só se aplica quando
        // há mesmo um código válido a substituir — sem isso, seria um bloqueio sem
        // razão a quem está à espera do primeiro.
        if (!automatico && pendente && user.getMfaCodeEnviadoEm() != null) {
            double segundos = (agora.toEpochMilli() - user.getMfaCodeEnviadoEm().toEpochMilli()) / 1000.0;
            if (segundos < INTERVALO_REENVIO_SEGUNDOS) {
                throw new BusinessRuleException(
                        "Já foi enviado um código há pouco. Aguarde " + (long) Math.ceil(INTERVALO_REENVIO_SEGUNDOS - segundos)
                                + " segundos antes de pedir outro — verifique também a pasta de spam.");
            }
        }

        String codigo = gerarCodigo();
        Instant expiraEm = agora.plus(Duration.ofMinutes(VALIDADE_MINUTOS));

        user.setMfaCodeHash(bcrypt.encode(codigo));
        user.setMfaCodeExpiraEm(expiraEm);
        user.setMfaCodeTentativas(0);
        user.setMfaCodeEnviadoEm(Instant.now());

        String assunto = "ativacao".equals(motivo)
                ? "Código para ativar a verificação em dois passos"
                : "Código de acesso KIXIMA";
        String corpo = EmailI18n.t(
                "O seu código é {codigo}. É válido durante {minutos} minutos e só pode ser usado uma vez. "
                        + "Se não foi você a pedi-lo, alguém sabe a sua senha — mude-a assim que puder.",
                user.getLocale(),
                Map.of("codigo", codigo, "minutos", VALIDADE_MINUTOS));

        try {
            emailDispatchService.enviarDireto(user.getEmail(), EmailI18n.t(assunto, user.getLocale()), corpo);
        } catch (RuntimeException err) {
            // O erro cru do fornecedor de email ("Key not found", "Sender not valid",
            // um erro de TLS) chegava à interface como "Ocorreu um erro interno" — que
            // não diz nada a quem está a tentar ativar, nem a quem tem de o resolver.
            // Aqui é dito por extenso, e o código pendente é apagado: guardá-lo seria
            // deixar a conta a apontar para um código que ninguém recebeu.
            limpar(user);
            throw new BusinessRuleException(
                    "Não foi possível enviar o código para " + mascarar(user.getEmail()) + ": " + err.getMessage() + ". "
                            + "Enquanto isto não estiver resolvido, não ative a verificação por email — ficaria sem forma de entrar.");
        }
        return new Envio(mascarar(user.getEmail()), expiraEm, VALIDADE_MINUTOS, null);
    }

    /**
     * Confirma o código. Devolve null se serve, ou a razão pela qual não serve.
     *
     * O código é consumido em QUALQUER desfecho: acertar gasta-o (é de uso único) e
     * esgotar as tentativas mata-o. Sem isso, dez minutos de validade dariam para
     * percorrer boa parte do milhão de combinações possíveis.
     */
    public String confirmarCodigo(User user, String codigo) {
        String c = (codigo == null ? "" : codigo).replaceAll("\\D", "");

        if (user.getMfaCodeHash() == null || user.getMfaCodeExpiraEm() == null) {
            return "Não há nenhum código pendente. Peça um código novo.";
        }
        if (Instant.now().isAfter(user.getMfaCodeExpiraEm())) {
            limpar(user);
            return "O código expirou (é válido " + VALIDADE_MINUTOS + " minutos). Peça um código novo.";
        }
        if (user.getMfaCodeTentativas() >= TENTATIVAS_MAX) {
            limpar(user);
            return "Demasiadas tentativas com este código. Peça um código novo.";
        }

        if (!bcrypt.matches(c, user.getMfaCodeHash())) {
            int tentativas = user.getMfaCodeTentativas() + 1;
            user.setMfaCodeTentativas(tentativas);
            int restantes = TENTATIVAS_MAX - tentativas;
            if (restantes <= 0) {
                limpar(user);
                return "Código incorreto. Foram esgotadas as tentativas — peça um código novo.";
            }
            return "Código incorreto. " + restantes + " tentativa(s) antes de o código ser anulado. "
                    + "Confirme que está a usar o email mais recente — os anteriores deixaram de servir.";
        }

        limpar(user);
        return null;
    }

    /** Apaga o código pendente. Não toca no estado da 2FA. */
    public void limpar(User user) {
        user.setMfaCodeHash(null);
        user.setMfaCodeExpiraEm(null);
        user.setMfaCodeTentativas(0);
    }
}
