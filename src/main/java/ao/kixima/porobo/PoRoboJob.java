package ao.kixima.porobo;

import ao.kixima.ops.OperationalAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Espelha backend/src/jobs/poRoboJob.js — corre diariamente às 06:00 e
 * prepara as POs cuja regra chegou à vez; nunca aprova nem paga.
 *
 * DESLIGADO POR OMISSÃO ({@code kixima.jobs.po-robot.enabled: false}) — aqui
 * a regra do plano é mesmo crítica: dois backends a correr o ciclo criariam
 * POs em duplicado (a reserva atómica só protege corridas que vêem a MESMA
 * base, o que os dois vêem — mas não vale a pena confiar nisso enquanto o
 * Node continuar dono do job).
 */
@Component
@ConditionalOnProperty(name = "kixima.jobs.po-robot.enabled", havingValue = "true")
public class PoRoboJob {

    private static final Logger log = LoggerFactory.getLogger(PoRoboJob.class);

    private final PoRoboService poRoboService;
    private final OperationalAlertService operationalAlertService;

    public PoRoboJob(PoRoboService poRoboService, OperationalAlertService operationalAlertService) {
        this.poRoboService = poRoboService;
        this.operationalAlertService = operationalAlertService;
    }

    @Scheduled(cron = "0 0 6 * * *")
    public void correr() {
        try {
            PoRoboService.CicloResultado resultado = poRoboService.executarCiclo();
            if (resultado.total() > 0) {
                log.info("PO Robot: ciclo concluído — total={} criadas={} falhas={}",
                        resultado.total(), resultado.criadas(), resultado.falhas().size());
            }
        } catch (Exception e) {
            log.error("Falha ao correr o ciclo do PO Robot: {}", e.getMessage());
            operationalAlertService.avisarFalha("PO_ROBOT", "o ciclo do Automatic PO Robot falhou",
                    "O processamento não correu.\n\nErro: " + e.getMessage() + "\n\n"
                            + "Regras ativas ficam por executar até à próxima corrida — nenhuma PO se perde, só atrasa.");
        }
    }
}
