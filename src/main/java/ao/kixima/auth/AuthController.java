package ao.kixima.auth;

import ao.kixima.audit.AuditService;
import ao.kixima.auth.dto.ChangePasswordRequest;
import ao.kixima.auth.dto.ForgotPasswordRequest;
import ao.kixima.auth.dto.LoginRequest;
import ao.kixima.auth.dto.ReenviarCodigoRequest;
import ao.kixima.auth.dto.ResetPasswordRequest;
import ao.kixima.auth.dto.TotpCodeRequest;
import ao.kixima.auth.dto.Verify2faRequest;
import ao.kixima.security.CurrentUser;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.SessionCookieUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Espelha backend/src/controllers/authController.js + backend/src/routes/authRoutes.js
 * — mesmos caminhos, mesmos verbos, mesmo comportamento de auditoria (regista
 * sucesso e falha de login; uma sequência de falhas é o primeiro sinal de um
 * ataque a uma conta concreta).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final SessionCookieUtil sessionCookieUtil;
    private final AuditService auditService;

    public AuthController(AuthService authService, SessionCookieUtil sessionCookieUtil, AuditService auditService) {
        this.authService = authService;
        this.sessionCookieUtil = sessionCookieUtil;
        this.auditService = auditService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest req, HttpServletResponse res) {
        String email = body.email().trim().toLowerCase();
        try {
            LoginResponse result = authService.login(email, body.password());
            boolean pedido2fa = Boolean.TRUE.equals(result.requires2fa());
            auditService.recordSafe(new AuditService.Entry(
                    new ao.kixima.audit.Actor(
                            result.user() == null ? null : result.user().id(),
                            result.user() == null ? null : result.user().name(),
                            result.user() == null ? null : result.user().role().name(),
                            result.user() == null ? null : result.user().companyId(),
                            req.getRemoteAddr()),
                    pedido2fa ? "LOGIN_2FA_PEDIDO" : "LOGIN_SUCESSO",
                    "User", result.user() == null ? null : result.user().id(), email,
                    auditService.contextoFrom(req)));
            // Sessão no cookie httpOnly — o token também sai no corpo para clientes
            // programáticos e para a suite de testes (ver comentário em authController.js).
            sessionCookieUtil.definir(res, result.token());
            return result;
        } catch (RuntimeException err) {
            auditService.recordSafe(new AuditService.Entry(
                    auditService.anonimoFrom(req), "LOGIN_FALHADO", "User", null, email,
                    Map.of("ip", req.getRemoteAddr(), "motivo", err.getClass().getSimpleName())));
            throw err;
        }
    }

    @GetMapping("/me")
    public Map<String, CurrentUser> me() {
        return Map.of("user", CurrentUserHolder.get());
    }

    @PatchMapping("/password")
    public Map<String, Boolean> changePassword(@Valid @RequestBody ChangePasswordRequest body, HttpServletRequest req) {
        CurrentUser user = CurrentUserHolder.get();
        authService.changePassword(user.id(), body.currentPassword(), body.newPassword());
        auditService.recordSafe(new AuditService.Entry(
                auditService.actorFrom(user, req), "SENHA_ALTERADA", "User", user.id(),
                user.email() != null ? user.email() : user.name(), auditService.contextoFrom(req)));
        return Map.of("ok", true);
    }

    @PostMapping("/logout")
    public Map<String, Boolean> logout(HttpServletRequest req, HttpServletResponse res) {
        CurrentUser user = CurrentUserHolder.get();
        authService.revokeSessions(user.id());
        auditService.recordSafe(new AuditService.Entry(
                auditService.actorFrom(user, req), "SESSOES_TERMINADAS", "User", user.id(),
                user.email() != null ? user.email() : user.name(), auditService.contextoFrom(req)));
        sessionCookieUtil.limpar(res);
        return Map.of("ok", true);
    }

    /** Endereço público real do serviço (com trust proxy) — para o link do email. Espelha publicBaseUrl(req). */
    private String publicBaseUrl(HttpServletRequest req) {
        String host = req.getHeader("host");
        return req.getScheme() + "://" + (host != null ? host : req.getServerName());
    }

    @PostMapping("/forgot-password")
    public Map<String, Object> forgotPassword(@Valid @RequestBody ForgotPasswordRequest body, HttpServletRequest req) {
        String email = body.email() == null ? "" : body.email().trim().toLowerCase();
        authService.requestPasswordReset(email, publicBaseUrl(req));
        auditService.recordSafe(new AuditService.Entry(
                auditService.anonimoFrom(req), "SENHA_RECUPERACAO_PEDIDA", "User", null, email,
                auditService.contextoFrom(req)));
        // Resposta SEMPRE igual, exista o email ou não (anti-enumeração) — tal como authController.js.
        return Map.of("ok", true, "message", "Se o email existir na plataforma, enviámos um link de recuperação.");
    }

    @PostMapping("/reset-password")
    public Map<String, Object> resetPassword(@Valid @RequestBody ResetPasswordRequest body, HttpServletRequest req) {
        AuthService.ResetResult result = authService.resetPassword(body.token(), body.password());
        auditService.recordSafe(new AuditService.Entry(
                new ao.kixima.audit.Actor(result.userId(), null, null, null, req.getRemoteAddr()),
                "SENHA_REPOSTA", "User", result.userId(), result.email(), auditService.contextoFrom(req)));
        return Map.of("ok", true);
    }

    // --- 2FA (TOTP / EMAIL) ----------------------------------------------

    @GetMapping("/2fa/status")
    public AuthService.TotpStatus totpStatus() {
        return authService.totpStatus(CurrentUserHolder.get().id());
    }

    /** Ativação por email: envia o código de 6 dígitos para o endereço da conta. */
    @PostMapping("/2fa/email/enviar")
    public MfaEmailService.Envio mfaEnviarCodigo() {
        return authService.enviarCodigoAtivacao(CurrentUserHolder.get().id());
    }

    /** Reenvio a partir do ecrã de login — ainda sem sessão, só com o desafio. */
    @PostMapping("/2fa/reenviar")
    public MfaEmailService.Envio mfaReenviarCodigo(@Valid @RequestBody ReenviarCodigoRequest body) {
        return authService.reenviarCodigoDoDesafio(body.challenge());
    }

    /** Reenvio já dentro da sessão (para desativar a 2FA por email). */
    @PostMapping("/2fa/email/reenviar")
    public MfaEmailService.Envio mfaReenviarCodigoSessao() {
        return authService.reenviarCodigo(CurrentUserHolder.get().id());
    }

    @PostMapping("/2fa/setup")
    public AuthService.TotpSetup totpSetup() {
        return authService.setupTotp(CurrentUserHolder.get().id());
    }

    @PostMapping("/2fa/enable")
    public AuthService.TotpEnableResult totpEnable(@Valid @RequestBody TotpCodeRequest body, HttpServletRequest req) {
        CurrentUser user = CurrentUserHolder.get();
        AuthService.TotpEnableResult result = authService.enableTotp(user.id(), body.code());
        auditService.recordSafe(new AuditService.Entry(
                auditService.actorFrom(user, req), "MFA_ATIVADA", "User", user.id(), user.name(), null));
        return result;
    }

    @PostMapping("/2fa/disable")
    public AuthService.TotpDisableResult totpDisable(@Valid @RequestBody TotpCodeRequest body, HttpServletRequest req) {
        CurrentUser user = CurrentUserHolder.get();
        AuthService.TotpDisableResult result = authService.disableTotp(user.id(), body.code());
        auditService.recordSafe(new AuditService.Entry(
                auditService.actorFrom(user, req), "MFA_DESATIVADA", "User", user.id(), user.name(), null));
        return result;
    }

    /** 2º passo do login (público): desafio + código → sessão completa. */
    @PostMapping("/2fa/verify")
    public LoginResponse totpVerify(@Valid @RequestBody Verify2faRequest body, HttpServletResponse res) {
        LoginResponse result = authService.verify2fa(body.challenge(), body.code());
        sessionCookieUtil.definir(res, result.token());
        return result;
    }
}
