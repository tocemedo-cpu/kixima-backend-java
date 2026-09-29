package ao.kixima.retention;

import ao.kixima.ops.OperationalAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Espelha backend/src/jobs/retencaoJob.js — aplica a política de retenção
 * periodicamente. Sem cron a hora fixa de propósito (mesma razão do Node:
 * um plano gratuito de hosting suspende sem tráfego; um atraso simples
 * corre sempre que o processo estiver de pé, e a política é idempotente).
 *
 * DESLIGADO POR OMISSÃO ({@code kixima.jobs.retencao.enabled: false}) —
 * o Node continua a correr esta limpeza em produção; ligar os dois ao
 * mesmo tempo duplicaria o trabalho (inofensivo aqui, por ser idempotente,
 * mas continua a ser a regra do plano para M6: nunca ligado por omissão
 * enquanto os dois backends coexistem).
 */
@Component
@ConditionalOnProperty(name = "kixima.jobs.retencao.enabled", havingValue = "true")
public class RetencaoJob {

    private static final Logger log = LoggerFactory.getLogger(RetencaoJob.class);

    private final RetentionService retentionService;
    private final OperationalAlertService operationalAlertService;

    public RetencaoJob(RetentionService retentionService, OperationalAlertService operationalAlertService) {
        this.retentionService = retentionService;
        this.operationalAlertService = operationalAlertService;
    }

    /** Uma hora depois do arranque (migrações/seed/primeira vaga de pedidos já passaram), depois a cada 24h. */
    @Scheduled(initialDelay = 60 * 60 * 1000, fixedDelay = 24 * 60 * 60 * 1000)
    public void correr() {
        try {
            retentionService.limpar();
        } catch (Exception e) {
            log.error("Retenção: a limpeza falhou — {}", e.getMessage());
            operationalAlertService.avisarFalha("RETENCAO_DADOS", "a limpeza de retenção de dados falhou",
                    "A limpeza automática não correu.\n\nErro: " + e.getMessage() + "\n\n"
                            + "A política de retenção publicada em /api/retencao promete apagar dados ao fim de um prazo. "
                            + "Enquanto isto falhar, a plataforma guarda mais do que diz guardar.");
        }
    }
}
