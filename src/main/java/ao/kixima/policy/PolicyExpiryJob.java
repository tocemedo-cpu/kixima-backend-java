package ao.kixima.policy;

import ao.kixima.ops.OperationalAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Espelha backend/src/jobs/policyExpiryJob.js — avisa Company Admin e
 * Financeiro antes da apólice KIXIMA→Cliente expirar. Cron diário às 07:00,
 * tal como o Node ({@code node-cron '0 7 * * *'}) — ao contrário do
 * RetencaoJob, este tem mesmo de correr a uma hora fixa (o aviso é lido por
 * humanos ao início do dia), por isso usa {@code cron}, não um atraso simples.
 *
 * DESLIGADO POR OMISSÃO ({@code kixima.jobs.expiracao-apolices.enabled: false})
 * — mesma razão dos outros jobs do M6: o Node continua a correr o seu
 * próprio, e ligar os dois ao mesmo tempo duplicaria o aviso.
 */
@Component
@ConditionalOnProperty(name = "kixima.jobs.expiracao-apolices.enabled", havingValue = "true")
public class PolicyExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(PolicyExpiryJob.class);

    private final PolicyService policyService;
    private final OperationalAlertService operationalAlertService;

    public PolicyExpiryJob(PolicyService policyService, OperationalAlertService operationalAlertService) {
        this.policyService = policyService;
        this.operationalAlertService = operationalAlertService;
    }

    @Scheduled(cron = "0 0 7 * * *")
    public void correr() {
        try {
            int count = policyService.enviarAvisosDeExpiracao();
            if (count > 0) {
                log.info("Alertas de expiração de apólice enviados: {}", count);
            }
        } catch (Exception e) {
            log.error("Falha ao processar alertas de expiração de apólice: {}", e.getMessage());
            operationalAlertService.avisarFalha("EXPIRACAO_APOLICES", "os avisos de expiração de apólice falharam",
                    "O processamento não correu.\n\nErro: " + e.getMessage() + "\n\n"
                            + "Uma apólice que expira sem aviso deixa uma transação sem cobertura — que é a garantia "
                            + "que a plataforma promete.");
        }
    }
}
