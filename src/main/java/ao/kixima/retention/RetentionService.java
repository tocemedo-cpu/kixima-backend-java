package ao.kixima.retention;

import ao.kixima.invite.EmployeeInviteRepository;
import ao.kixima.invite.InviteStatus;
import ao.kixima.notification.NotificationRepository;
import ao.kixima.retention.dto.RetentionCleanupResult;
import ao.kixima.retention.dto.RetentionPolicyItemDto;
import ao.kixima.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Espelha backend/src/services/retencaoService.js — quanto tempo cada
 * coisa fica guardada, e a limpeza que o cumpre. A política é a fonte
 * única: {@code GET /api/retencao} (RetentionController) lê-a daqui, e
 * {@link #limpar()} aplica-a — para o texto publicado nunca divergir do
 * comportamento real.
 *
 * NÃO SE APAGA (e não deve): trilho de auditoria nem registos financeiros
 * (ordens, faturas, pagamentos) — obrigação de conservação contabilística
 * e prova das transações. Os prazos ficam configuráveis por variável de
 * ambiente (mesmos nomes do Node), nunca constantes fixas no código.
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final NotificationRepository notificationRepository;
    private final EmployeeInviteRepository employeeInviteRepository;
    private final UserRepository userRepository;

    private final int diasNotificacoes;
    private final int diasConvites;
    private final int diasCodigos2fa;

    public RetentionService(NotificationRepository notificationRepository, EmployeeInviteRepository employeeInviteRepository,
                             UserRepository userRepository,
                             @Value("${kixima.retencao.notificacoes-dias:180}") int diasNotificacoes,
                             @Value("${kixima.retencao.convites-dias:90}") int diasConvites,
                             @Value("${kixima.retencao.codigos-dias:1}") int diasCodigos2fa) {
        this.notificationRepository = notificationRepository;
        this.employeeInviteRepository = employeeInviteRepository;
        this.userRepository = userRepository;
        this.diasNotificacoes = diasNotificacoes;
        this.diasConvites = diasConvites;
        this.diasCodigos2fa = diasCodigos2fa;
    }

    /** A política, por extenso — para {@code GET /api/retencao} e para {@link #limpar()} lerem do mesmo sítio. */
    public List<RetentionPolicyItemDto> politica() {
        return List.of(
                new RetentionPolicyItemDto("notificacoes", "Notificações já lidas", prazoTexto(diasNotificacoes), diasNotificacoes,
                        "São avisos operacionais. Passado meio ano deixam de ter utilidade e só acumulam."),
                new RetentionPolicyItemDto("convites", "Convites de funcionário expirados ou cancelados", prazoTexto(diasConvites), diasConvites,
                        "Contêm o email de alguém que nunca chegou a ter conta. Não há motivo para os manter."),
                new RetentionPolicyItemDto("codigos2fa", "Códigos de verificação em dois passos enviados por email", prazoTexto(diasCodigos2fa), diasCodigos2fa,
                        "Valem 10 minutos. O registo do que já expirou não serve para nada."),
                new RetentionPolicyItemDto("conta", "Dados da sua conta (nome, email, foto, preferências)", "Enquanto a conta existir / conservação legal", null,
                        "Ficam enquanto a conta existir. Pode exportá-los ou eliminá-los a qualquer momento "
                                + "em Configurações → Segurança, sem pedir nada a ninguém."),
                new RetentionPolicyItemDto("financeiro", "Ordens de compra, faturas, pagamentos e trilho de auditoria", "Enquanto a conta existir / conservação legal", null,
                        "Conservados por obrigação legal de conservação contabilística e porque são a prova "
                                + "das transações. Ao eliminar a sua conta, estes registos mantêm-se mas deixam de o "
                                + "identificar: o nome é substituído e a ligação à pessoa desaparece."));
    }

    /** "1 dias" lê-se como um descuido — e num texto legal um descuido tira credibilidade ao resto. */
    private String prazoTexto(int dias) {
        return dias + " " + (dias == 1 ? "dia" : "dias");
    }

    /** Aplica a política. Nunca toca em registos financeiros nem no trilho de auditoria. */
    @Transactional
    public RetentionCleanupResult limpar() {
        Instant agora = Instant.now();
        int notificacoes = notificationRepository.deleteByReadAtNotNullAndBefore(agora.minus(Duration.ofDays(diasNotificacoes)));
        int convites = employeeInviteRepository.deleteMortosAntesDe(
                List.of(InviteStatus.EXPIRADO, InviteStatus.CANCELADO), agora.minus(Duration.ofDays(diasConvites)));
        int codigos2fa = userRepository.limparCodigos2faExpirados(agora.minus(Duration.ofDays(diasCodigos2fa)));

        int total = notificacoes + convites + codigos2fa;
        if (total > 0) {
            log.info("Retenção: {} registo(s) eliminado(s) (notificacoes={}, convites={}, codigos2fa={})",
                    total, notificacoes, convites, codigos2fa);
        }
        return new RetentionCleanupResult(notificacoes, convites, codigos2fa, total, agora);
    }
}
