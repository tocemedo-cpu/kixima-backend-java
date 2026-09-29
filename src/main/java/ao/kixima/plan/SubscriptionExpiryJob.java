package ao.kixima.plan;

import ao.kixima.ops.OperationalAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Espelha backend/src/jobs/subscriptionExpiryJob.js — mesmo padrão do
 * PolicyExpiryJob (cron diário às 07:00, mesma hora do aviso de apólices).
 *
 * DESLIGADO POR OMISSÃO ({@code kixima.jobs.expiracao-subscricoes.enabled: false})
 * — o Node continua a correr o seu próprio; ligar os dois duplicaria o aviso.
 */
@Component
@ConditionalOnProperty(name = "kixima.jobs.expiracao-subscricoes.enabled", havingValue = "true")
public class SubscriptionExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionExpiryJob.class);

    private final SubscriptionExpiryService subscriptionExpiryService;
    private final OperationalAlertService operationalAlertService;

    public SubscriptionExpiryJob(SubscriptionExpiryService subscriptionExpiryService,
                                  OperationalAlertService operationalAlertService) {
        this.subscriptionExpiryService = subscriptionExpiryService;
        this.operationalAlertService = operationalAlertService;
    }

    @Scheduled(cron = "0 0 7 * * *")
    public void correr() {
        try {
            subscriptionExpiryService.enviarAvisosDeExpiracao();
        } catch (Exception e) {
            log.error("Falha ao processar avisos de expiração de subscrição: {}", e.getMessage());
            operationalAlertService.avisarFalha("EXPIRACAO_SUBSCRICOES", "os avisos de expiração de subscrição falharam",
                    "O processamento não correu.\n\nErro: " + e.getMessage() + "\n\n"
                            + "Uma subscrição que vence sem aviso é receita perdida em silêncio — a empresa não sabe "
                            + "que tem de renovar, e a KIXIMA só descobre quando alguém olhar para a fila do Admin.");
        }
    }
}
