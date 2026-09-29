package ao.kixima.admin;

import ao.kixima.audit.AuditService;
import ao.kixima.backup.BackupService;
import ao.kixima.backup.BackupVerificationService;
import ao.kixima.common.error.AppException;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.RequirePermission;
import ao.kixima.security.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

import static ao.kixima.security.AdminArea.OPERACOES;
import static ao.kixima.security.PersonaRole.ADMIN_SISTEMA;

/**
 * Espelha o troço de cópias de segurança de adminRoutes.js ({@code POST /backup},
 * {@code POST /backup/verificar}) — o botão que confirma, de uma vez, que o
 * pg_dump está na imagem, que a ligação serve e que o bucket privado recebe
 * o ficheiro, antes de se confiar no agendamento das 03:00.
 */
@RestController
@RequestMapping("/api/admin")
public class BackupAdminController {

    private final BackupService backupService;
    private final BackupVerificationService backupVerificationService;
    private final AuditService auditService;

    public BackupAdminController(BackupService backupService, BackupVerificationService backupVerificationService,
                                  AuditService auditService) {
        this.backupService = backupService;
        this.backupVerificationService = backupVerificationService;
        this.auditService = auditService;
    }

    private static Map<String, Object> erro(String mensagem) {
        return Map.of("error", Map.of("message", mensagem));
    }

    @PostMapping("/backup")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(OPERACOES)
    public ResponseEntity<Object> copiarAgora(HttpServletRequest req) {
        String motivo = backupService.motivoParaNaoCorrer();
        if (motivo != null) return ResponseEntity.unprocessableEntity().body(erro(motivo));

        long inicio = System.currentTimeMillis();
        try {
            BackupService.Resultado r = backupService.copiar();
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("megabytes", r.megabytes());
            detail.put("segundos", Math.round(r.segundos() * 10) / 10.0);
            auditService.recordSafe(new AuditService.Entry(auditService.actorFrom(CurrentUserHolder.get(), req),
                    "COPIA_SEGURANCA_MANUAL", "Backup", null, null, detail));
            Map<String, Object> corpo = new LinkedHashMap<>(detail);
            corpo.put("destino", r.destino());
            return ResponseEntity.ok(corpo);
        } catch (Exception err) {
            // O erro cru do pg_dump não diz o que corrigir; é o que aqui se devolve, para ser resolúvel sem ir ao log.
            double segundos = Math.round((System.currentTimeMillis() - inicio) / 100.0) / 10.0;
            return ResponseEntity.status(502).body(erro("A cópia falhou ao fim de " + segundos + "s: " + err.getMessage()));
        }
    }

    @PostMapping("/backup/verificar")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(OPERACOES)
    public ResponseEntity<Object> verificar(HttpServletRequest req) {
        try {
            BackupVerificationService.Verificacao r = backupVerificationService.verificar();
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("tabelas", r.tabelasNoDump());
            detail.put("megabytes", r.megabytes());
            auditService.recordSafe(new AuditService.Entry(auditService.actorFrom(CurrentUserHolder.get(), req),
                    "COPIA_SEGURANCA_VERIFICADA", "Backup", null, null, detail));
            return ResponseEntity.ok(r);
        } catch (AppException err) {
            throw err; // envelope normal (BusinessRule → 400), o mesmo `err.status || 400` do Node
        } catch (Exception err) {
            return ResponseEntity.badRequest().body(erro(err.getMessage()));
        }
    }
}
