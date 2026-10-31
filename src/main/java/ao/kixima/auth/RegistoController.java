package ao.kixima.auth;

import ao.kixima.audit.AuditService;
import ao.kixima.auth.dto.RegisterRequest;
import ao.kixima.auth.dto.ResendVerificationRequest;
import ao.kixima.auth.dto.VerifyEmailRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Registo em dois passos (conta + confirmação do email). Os três endpoints são públicos — ver {@code PublicPaths}. */
@RestController
@RequestMapping("/api/auth")
public class RegistoController {

    private final RegistoService registoService;
    private final AuditService auditService;

    public RegistoController(RegistoService registoService, AuditService auditService) {
        this.registoService = registoService;
        this.auditService = auditService;
    }

    /** Endereço público real do serviço — para o link do email quando APP_URL não está definida. */
    private static String publicBaseUrl(HttpServletRequest req) {
        String host = req.getHeader("host");
        return req.getScheme() + "://" + (host != null ? host : req.getServerName());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@Valid @RequestBody RegisterRequest body, HttpServletRequest req) {
        RegistoService.Registado r = registoService.registar(body, publicBaseUrl(req));
        Map<String, Object> detail = new LinkedHashMap<>(auditService.contextoFrom(req));
        detail.put("tipo", r.type().name());
        auditService.recordSafe(new AuditService.Entry(auditService.anonimoFrom(req), "REGISTO_CONTA_CRIADA", "User", r.userId(), r.email(), detail));
        return Map.of("ok", true, "email", r.email(), "emailVerificationRequired", true);
    }

    @PostMapping("/verify-email")
    public Map<String, Object> verifyEmail(@Valid @RequestBody VerifyEmailRequest body, HttpServletRequest req) {
        String email = registoService.confirmarEmail(body.token());
        auditService.recordSafe(new AuditService.Entry(auditService.anonimoFrom(req), "EMAIL_CONFIRMADO", "User", null, email,
                auditService.contextoFrom(req)));
        return Map.of("ok", true, "email", email);
    }

    @PostMapping("/resend-verification")
    public Map<String, Object> resendVerification(@Valid @RequestBody ResendVerificationRequest body, HttpServletRequest req) {
        registoService.reenviarConfirmacao(body.email(), publicBaseUrl(req));
        // Resposta SEMPRE igual, exista a conta ou não (anti-enumeração).
        return Map.of("ok", true, "message", "Se existir uma conta por confirmar com este email, enviámos um novo link de confirmação.");
    }
}
