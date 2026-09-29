package ao.kixima.user;

import ao.kixima.common.error.UnauthorizedException;
import ao.kixima.notification.EmailDispatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Espelha backend/src/services/loginAttemptService.js — bloqueio
 * progressivo de conta por tentativas de entrada falhadas, persistido no
 * próprio utilizador (não em memória — sobrevive a um reinício do
 * processo, tal como o Node).
 *
 * AS TRÊS DECISÕES QUE MOLDAM ISTO (as mesmas do Node): o bloqueio é sempre
 * temporário, o contador esquece, e o titular é avisado por email — é a
 * única parte que apanha um ataque que ACERTA: se alguém está a martelar a
 * conta de uma pessoa, essa pessoa é quem consegue reconhecer que não é ela.
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    /** Falhas toleradas antes do primeiro bloqueio. */
    public static final int LIMIAR = 5;

    /** A escada, em minutos. */
    public static final List<Integer> ESCADA_MINUTOS = List.of(1, 2, 5, 15, 30, 60);

    /** Sem falhas durante este tempo, o contador volta a zero. */
    public static final int JANELA_DE_ESQUECIMENTO_MIN = 30;

    /** Não se avisa o titular mais do que uma vez por hora, aconteça o que acontecer. */
    private static final int INTERVALO_DO_AVISO_MIN = 60;

    private static final long MIN_MS = 60 * 1000L;

    private final EmailDispatchService emailDispatchService;

    public LoginAttemptService(EmailDispatchService emailDispatchService) {
        this.emailDispatchService = emailDispatchService;
    }

    public int minutosDeBloqueio(int falhas) {
        int passo = falhas - LIMIAR;
        if (passo < 0) return 0;
        return ESCADA_MINUTOS.get(Math.min(passo, ESCADA_MINUTOS.size() - 1));
    }

    private boolean esqueceu(User user, Instant agora) {
        if (user.getUltimaFalhaEm() == null) return true;
        return agora.toEpochMilli() - user.getUltimaFalhaEm().toEpochMilli() > JANELA_DE_ESQUECIMENTO_MIN * MIN_MS;
    }

    /**
     * Recusa a entrada se a conta estiver bloqueada. Corre ANTES da
     * comparação da senha (bcrypt é caro de propósito).
     */
    public void assertNaoBloqueado(User user, Instant agora) {
        if (user.getBloqueadoAte() == null) return;
        if (!user.getBloqueadoAte().isAfter(agora)) return;
        long faltamMs = Duration.between(agora, user.getBloqueadoAte()).toMillis();
        long faltamMin = (long) Math.ceil(faltamMs / 60_000.0);
        throw new UnauthorizedException(
                "Demasiadas tentativas falhadas. Esta conta está bloqueada durante " + faltamMin + " minuto(s). "
                        + "Se não foi você a tentar entrar, mude a senha assim que conseguir aceder.");
    }

    public void assertNaoBloqueado(User user) {
        assertNaoBloqueado(user, Instant.now());
    }

    /**
     * Regista uma senha errada. Muta o `user` gerido pelo JPA (a
     * transação da chamada persiste as alterações) — mesmo padrão do
     * Node, que faz um único UPDATE com o novo estado. Não lança: quem
     * chama decide o que dizer a seguir.
     */
    public void registarFalha(User user, Instant agora) {
        int base = esqueceu(user, agora) ? 0 : user.getFalhasSeguidas();
        int falhas = base + 1;
        int minutos = minutosDeBloqueio(falhas);
        Instant bloqueadoAte = minutos > 0 ? agora.plus(Duration.ofMinutes(minutos)) : null;

        user.setFalhasSeguidas(falhas);
        user.setUltimaFalhaEm(agora);
        user.setBloqueadoAte(bloqueadoAte);

        if (bloqueadoAte != null) {
            // Fica no registo com o email: quem investiga um incidente precisa de saber
            // QUE conta estava a ser martelada, e isso não é um segredo — é o alvo.
            log.warn("Conta bloqueada por tentativas falhadas: {} ({} falhas, {} min)", user.getEmail(), falhas, minutos);
            avisarTitular(user, falhas, minutos, agora);
        }
    }

    public void registarFalha(User user) {
        registarFalha(user, Instant.now());
    }

    /**
     * Avisa a pessoa de que alguém anda a tentar entrar na conta dela.
     *
     * Falhar o envio não pode desfazer o bloqueio — o bloqueio é a proteção, o
     * email é a cortesia. Por isso o erro é registado e engolido (incluindo o
     * 422 que enviarDireto lança com EMAIL_PROVIDER=console: fica no log,
     * tal como no Node).
     */
    private void avisarTitular(User user, int falhas, int minutos, Instant agora) {
        long ultimo = user.getAvisoBloqueioEm() == null ? 0 : user.getAvisoBloqueioEm().toEpochMilli();
        if (agora.toEpochMilli() - ultimo < INTERVALO_DO_AVISO_MIN * MIN_MS) return;

        try {
            user.setAvisoBloqueioEm(agora);
            emailDispatchService.enviarDireto(
                    user.getEmail(),
                    "Tentativas de entrada na sua conta KIXIMA",
                    String.join("\n",
                            "Olá " + user.getName() + ",", "",
                            "Houve " + falhas + " tentativas seguidas de entrar na sua conta com a senha errada.",
                            "Por segurança, a conta ficou bloqueada durante " + minutos + " minuto(s).",
                            "",
                            "Se foi você e se enganou na senha, é só esperar e tentar de novo — ou usar",
                            "\"Esqueci-me da senha\" para definir uma nova.",
                            "",
                            "SE NÃO FOI VOCÊ: alguém sabe o seu email e está a adivinhar a senha.",
                            "Mude a senha assim que conseguir entrar e ative a verificação em dois passos",
                            "em Configurações → Segurança, para que a senha deixe de ser suficiente.",
                            "", "Equipe Kixima."));
        } catch (RuntimeException err) {
            log.error("Não foi possível avisar {} do bloqueio: {}", user.getEmail(), err.getMessage());
        }
    }

    /** Entrada bem sucedida: apaga o rasto. */
    public void limpar(User user) {
        if (user.getFalhasSeguidas() == 0 && user.getBloqueadoAte() == null) return;
        user.setFalhasSeguidas(0);
        user.setBloqueadoAte(null);
        user.setUltimaFalhaEm(null);
    }
}
