package ao.kixima.security;

import ao.kixima.ops.OperationalAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Espelha backend/src/jobs/mfaLembreteJob.js — pergunta "há alguém por
 * avisar hoje?" de hora a hora, em vez de um cron a hora fixa: no plano
 * gratuito do Render o serviço suspende sem tráfego, e um agendamento a
 * hora fixa não corre quando o processo está adormecido. A proteção contra
 * insistir vive no próprio {@link MfaReminderService} (ninguém é lembrado
 * duas vezes em 24 horas), não aqui.
 *
 * DESLIGADO POR OMISSÃO ({@code kixima.jobs.lembretes-2fa.enabled: false})
 * — o Node continua a correr o seu próprio; ligar os dois duplicaria o aviso.
 */
@Component
@ConditionalOnProperty(name = "kixima.jobs.lembretes-2fa.enabled", havingValue = "true")
public class MfaReminderJob {

    private static final Logger log = LoggerFactory.getLogger(MfaReminderJob.class);
    private static final long ESPERA_APOS_ARRANQUE_MS = 5 * 60 * 1000;
    private static final long INTERVALO_MS = 60 * 60 * 1000;

    private final MfaReminderService mfaReminderService;
    private final OperationalAlertService operationalAlertService;

    public MfaReminderJob(MfaReminderService mfaReminderService, OperationalAlertService operationalAlertService) {
        this.mfaReminderService = mfaReminderService;
        this.operationalAlertService = operationalAlertService;
    }

    @Scheduled(initialDelay = ESPERA_APOS_ARRANQUE_MS, fixedDelay = INTERVALO_MS)
    public void correr() {
        try {
            int enviados = mfaReminderService.lembretesAutomaticos();
            if (enviados > 0) {
                log.info("Lembrete de 2FA enviado a {} conta(s).", enviados);
            }
        } catch (Exception e) {
            log.error("Falha ao processar lembretes de 2FA: {}", e.getMessage());
            operationalAlertService.avisarFalha("LEMBRETES_2FA", "os lembretes de 2FA falharam",
                    "O processamento não correu.\n\nErro: " + e.getMessage() + "\n\n"
                            + "Sem lembrete, quem aprova ordens e autoriza pagamentos pode chegar à data limite "
                            + "sem ter configurado a 2FA — e ficar de fora no dia em que passa a ser exigida.");
        }
    }
}
