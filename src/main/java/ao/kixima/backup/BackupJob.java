package ao.kixima.backup;

import ao.kixima.ops.OperationalAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.Map;
import java.util.TimeZone;

/**
 * Espelha o agendamento de backend/src/jobs/backupJob.js — OPT-IN por
 * {@code BACKUP_CRON} (5 campos, como o node-cron; convertido para os 6 do
 * Spring), mais a rede de segurança do plano gratuito: de 30 em 30 minutos
 * pergunta "já passou demasiado tempo desde a última?", porque o serviço
 * dorme às 03:00 e acorda com o primeiro utilizador do dia.
 *
 * DESLIGADO POR OMISSÃO ({@code kixima.jobs.backup.enabled: false}) e, mesmo
 * ligado, só faz alguma coisa com BACKUP_CRON e S3 — as mesmas condições do Node.
 */
@Component
@ConditionalOnProperty(name = "kixima.jobs.backup.enabled", havingValue = "true")
public class BackupJob implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(BackupJob.class);
    private static final long ESPERA_APOS_ARRANQUE_MS = 3 * 60 * 1000;
    private static final long INTERVALO_VERIFICACAO_MS = 30 * 60 * 1000;

    private final BackupService backupService;
    private final OperationalAlertService operationalAlertService;
    private final TaskScheduler taskScheduler;
    private final String cronCru;

    public BackupJob(BackupService backupService, OperationalAlertService operationalAlertService,
                      TaskScheduler taskScheduler, @Value("${kixima.backup.cron:}") String cronCru) {
        this.backupService = backupService;
        this.operationalAlertService = operationalAlertService;
        this.taskScheduler = taskScheduler;
        this.cronCru = cronCru;
    }

    @Override
    public void afterSingletonsInstantiated() {
        String expressao = backupService.cron();
        if (expressao.isBlank()) return;

        if (BackupService.precisouDeLimpeza(cronCru)) {
            log.warn("BACKUP_CRON tinha espaços, aspas ou texto a mais; foi lido como \"{}\". Vale a pena arrumar o valor no painel.", expressao);
        }
        if (!BackupService.cronValido(expressao)) {
            log.error("BACKUP_CRON inválido (\"{}\") — a cópia automática NÃO vai correr.", expressao);
            return;
        }
        String motivo = backupService.motivoParaNaoCorrer();
        if (motivo != null) {
            log.error("BACKUP_CRON definido mas a cópia automática não vai correr. {}", motivo);
            return;
        }

        log.info("Cópia de segurança automática agendada: {} (UTC). Recuperação de cópias perdidas ativa: se a última tiver "
                + "mais de {}h, corre assim que o serviço estiver acordado.", expressao, backupService.idadeMaximaHoras());
        taskScheduler.schedule(this::copiaAgendada,
                new CronTrigger(BackupService.normalizarCron(expressao), TimeZone.getTimeZone(ZoneOffset.UTC)));
    }

    /** A rede de segurança: o serviço dorme às 03:00, mas acorda com o primeiro utilizador do dia. */
    @Scheduled(initialDelay = ESPERA_APOS_ARRANQUE_MS, fixedDelay = INTERVALO_VERIFICACAO_MS)
    public void verificarAtraso() {
        try {
            backupService.verificarAtraso();
        } catch (Exception ignorado) {
            // mesmo `.catch(() => {})` do Node — verificarAtraso já regista as falhas que importam
        }
    }

    void copiaAgendada() {
        try {
            BackupService.Resultado r = backupService.copiar();
            log.info("Cópia de segurança concluída em {}s — {} MB (destino={})", Math.round(r.segundos() * 10) / 10.0,
                    r.megabytes(), r.destino());
        } catch (Exception err) {
            // Uma cópia que falha em silêncio é o mesmo que não existir.
            log.error("FALHA NA CÓPIA DE SEGURANÇA — a base ficou sem cópia hoje: {}", err.getMessage());
            backupService.registar(BackupService.ACAO_FALHOU, Map.of("erro", BackupService.resumo(err.getMessage())));
            operationalAlertService.avisarFalha("COPIA_SEGURANCA", "a cópia de segurança falhou",
                    "A cópia automática da base de dados não correu.\n\nErro: " + err.getMessage() + "\n\n"
                            + "Enquanto isto não for resolvido, a plataforma está a acumular dias sem cópia. "
                            + "Pode forçar uma agora em Prontidão para produção → \"Fazer cópia agora\".");
        }
    }
}
