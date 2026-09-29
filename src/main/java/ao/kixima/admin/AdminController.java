package ao.kixima.admin;

import ao.kixima.admin.dto.AdminInviteDto;
import ao.kixima.admin.dto.AdminRequests;
import ao.kixima.admin.dto.AdminUserDto;
import ao.kixima.admin.dto.ResolvedAdminInviteDto;
import ao.kixima.audit.Actor;
import ao.kixima.audit.AuditService;
import ao.kixima.common.error.ValidationException;
import ao.kixima.notification.EmailDispatchService;
import ao.kixima.security.AdminArea;
import ao.kixima.security.CurrentUser;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.RequirePermission;
import ao.kixima.security.RequireRole;
import ao.kixima.security.RequireSuperAdmin;
import ao.kixima.user.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ao.kixima.security.AdminArea.OPERACOES;
import static ao.kixima.security.PersonaRole.ADMIN_SISTEMA;

/**
 * Espelha o troço de backend/src/routes/adminRoutes.js dos convites de
 * assessor (público + Super Admin), utilizadores da plataforma, áreas,
 * actividades do sistema, prontidão e email de teste. Os restantes troços
 * vivem em AdminAuditController, BackupAdminController, MfaReminderController,
 * PlatformFeeAdminController e FeedbackAdminController.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;
    private final AuditService auditService;
    private final ProntidaoService prontidaoService;
    private final EmailDispatchService emailDispatchService;

    public AdminController(AdminService adminService, AuditService auditService, ProntidaoService prontidaoService,
                           EmailDispatchService emailDispatchService) {
        this.adminService = adminService;
        this.auditService = auditService;
        this.prontidaoService = prontidaoService;
        this.emailDispatchService = emailDispatchService;
    }

    private String publicBaseUrl(HttpServletRequest req) {
        String host = req.getHeader("host");
        return req.getScheme() + "://" + (host != null ? host : req.getServerName());
    }

    // --- Público: o token opaco é a autorização ---------------------------

    @GetMapping("/invite/{token}")
    public ResolvedAdminInviteDto resolveInvite(@PathVariable String token) {
        return adminService.resolveAdminInvite(token);
    }

    @PostMapping("/invite/{token}/accept")
    public ResponseEntity<Map<String, Object>> acceptInvite(@PathVariable String token,
                                                            @RequestBody(required = false) AdminRequests.AcceptAdminInvite body,
                                                            HttpServletRequest req) {
        if (body == null || body.password() == null || body.password().isBlank()) throw new ValidationException("Indique uma senha.");
        if (!Boolean.TRUE.equals(body.termsAccepted())) {
            throw new ValidationException("É necessário aceitar os Termos de Uso e a Política de Privacidade.");
        }
        User user = adminService.acceptAdminInvite(token, body.password());
        Actor anonimo = auditService.anonimoFrom(req);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("areas", user.getAdminAreas());
        detail.putAll(auditService.contextoFrom(req));
        auditService.recordSafe(new AuditService.Entry(
                new Actor(user.getId(), user.getName(), anonimo.actorRole(), anonimo.companyId(), anonimo.ip()),
                "CONVITE_ADMIN_ACEITO", "User", user.getId(), user.getEmail(), detail));
        return ResponseEntity.status(HttpStatus.CREATED).body(AdminService.utilizadorAceite(user));
    }

    // --- Só o Super Admin ------------------------------------------------

    @GetMapping("/users")
    @RequireRole({ADMIN_SISTEMA})
    @RequireSuperAdmin
    public List<AdminUserDto> listUsers() {
        return adminService.listUsers();
    }

    @PatchMapping("/users/{id}/status")
    @RequireRole({ADMIN_SISTEMA})
    @RequireSuperAdmin
    public Map<String, Object> setUserStatus(@PathVariable String id, @RequestBody(required = false) AdminRequests.SetStatus body,
                                             HttpServletRequest req) {
        CurrentUser me = CurrentUserHolder.get();
        Map<String, Object> user = adminService.setUserStatus(id, body != null && Boolean.TRUE.equals(body.active()), me.id());
        boolean active = Boolean.TRUE.equals(user.get("active"));
        auditService.recordSafe(new AuditService.Entry(auditService.actorFrom(me, req),
                active ? "UTILIZADOR_DESBLOQUEADO" : "UTILIZADOR_BLOQUEADO", "User", (String) user.get("id"),
                (String) user.get("name"), Map.of("papel", user.get("role"))));
        return user;
    }

    @PatchMapping("/users/{id}/areas")
    @RequireRole({ADMIN_SISTEMA})
    @RequireSuperAdmin
    public ResponseEntity<?> setUserAreas(@PathVariable String id, @RequestBody(required = false) AdminRequests.SetAreas body,
                                          HttpServletRequest req) {
        List<String> areas = body == null || body.areas() == null ? List.of() : body.areas();
        List<String> invalidas = areas.stream().filter(a -> !AdminArea.AREAS_ADMIN.contains(a)).toList();
        if (!invalidas.isEmpty()) {
            return ResponseEntity.status(422).body(Map.of("error", Map.of("message", "Área desconhecida: " + String.join(", ", invalidas) + ".")));
        }
        CurrentUser me = CurrentUserHolder.get();
        Map<String, Object> user = adminService.setUserAreas(id, areas, me.id());
        auditService.recordSafe(new AuditService.Entry(auditService.actorFrom(me, req), "AREAS_DE_ADMIN_ALTERADAS", "User",
                (String) user.get("id"), (String) user.get("name"), Map.of("areas", user.get("adminAreas"))));
        return ResponseEntity.ok(user);
    }

    @GetMapping("/invites")
    @RequireRole({ADMIN_SISTEMA})
    @RequireSuperAdmin
    public List<AdminInviteDto> listInvites() {
        return adminService.listAdminInvites();
    }

    @PostMapping("/invites")
    @RequireRole({ADMIN_SISTEMA})
    @RequireSuperAdmin
    public ResponseEntity<AdminInviteDto> createInvite(@RequestBody(required = false) AdminRequests.CreateAdminInvite body,
                                                       HttpServletRequest req) {
        // createAdminInviteSchema: nome ≥ 2, email válido, adminAreas não vazio e só de AREAS_ADMIN.
        if (body == null || body.name() == null || body.name().trim().length() < 2) throw new ValidationException("Indique o nome.");
        if (body.email() == null || !body.email().trim().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw new ValidationException("Email inválido.");
        if (body.adminAreas() == null || body.adminAreas().isEmpty()) throw new ValidationException("Selecione pelo menos uma área administrativa.");
        List<String> invalidas = body.adminAreas().stream().filter(a -> !AdminArea.AREAS_ADMIN.contains(a)).toList();
        if (!invalidas.isEmpty()) throw new ValidationException("Área desconhecida: " + String.join(", ", invalidas) + ".");
        CurrentUser me = CurrentUserHolder.get();
        AdminInviteDto invite = adminService.createAdminInvite(body.name(), body.email(), body.adminAreas(), me.id(), publicBaseUrl(req));
        auditService.recordSafe(new AuditService.Entry(auditService.actorFrom(me, req), "CONVITE_ADMIN_CRIADO", "EmployeeInvite",
                invite.id(), invite.email(), Map.of("areas", invite.adminAreas())));
        return ResponseEntity.status(HttpStatus.CREATED).body(invite);
    }

    @PostMapping("/invites/{id}/resend")
    @RequireRole({ADMIN_SISTEMA})
    @RequireSuperAdmin
    public AdminInviteDto resendInvite(@PathVariable String id, HttpServletRequest req) {
        AdminInviteDto invite = adminService.resendAdminInvite(id, publicBaseUrl(req));
        auditService.recordSafe(new AuditService.Entry(auditService.actorFrom(CurrentUserHolder.get(), req), "CONVITE_ADMIN_REENVIADO",
                "EmployeeInvite", invite.id(), invite.email(), null));
        return invite;
    }

    @PostMapping("/invites/{id}/cancel")
    @RequireRole({ADMIN_SISTEMA})
    @RequireSuperAdmin
    public AdminInviteDto cancelInvite(@PathVariable String id, HttpServletRequest req) {
        AdminInviteDto invite = adminService.cancelAdminInvite(id);
        auditService.recordSafe(new AuditService.Entry(auditService.actorFrom(CurrentUserHolder.get(), req), "CONVITE_ADMIN_CANCELADO",
                "EmployeeInvite", invite.id(), invite.email(), null));
        return invite;
    }

    // --- Operações --------------------------------------------------------

    @GetMapping("/activities")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(OPERACOES)
    public Map<String, Object> activities() {
        return adminService.systemActivities();
    }

    @GetMapping("/prontidao")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(OPERACOES)
    public Map<String, Object> prontidao() {
        return prontidaoService.verificar();
    }

    @PostMapping("/email-teste")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(OPERACOES)
    public Map<String, Object> emailTeste(HttpServletRequest req) {
        CurrentUser me = CurrentUserHolder.get();
        EmailDispatchService.EnvioDireto r = emailDispatchService.enviarEmailDeTeste(me.email());
        auditService.recordSafe(new AuditService.Entry(auditService.actorFrom(me, req), "EMAIL_TESTE_ENVIADO", "Email",
                null, r.para(), Map.of("provider", r.provider())));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", r.provider());
        m.put("para", r.para());
        m.put("remetente", r.remetente());
        return m;
    }
}
