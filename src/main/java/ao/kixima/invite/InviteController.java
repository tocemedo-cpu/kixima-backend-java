package ao.kixima.invite;

import ao.kixima.audit.Actor;
import ao.kixima.audit.AuditService;
import ao.kixima.common.error.ErrosDeCampos;
import ao.kixima.common.error.ValidationException;
import ao.kixima.invite.dto.AcceptInviteRequest;
import ao.kixima.invite.dto.CompanyUserDto;
import ao.kixima.invite.dto.CreateInviteRequest;
import ao.kixima.invite.dto.InviteCreatedDto;
import ao.kixima.invite.dto.InviteDto;
import ao.kixima.invite.dto.ResolvedInviteDto;
import ao.kixima.invite.dto.SetUserStatusRequest;
import ao.kixima.security.CurrentUser;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static ao.kixima.security.PersonaRole.COMPANY_ADMIN;
import static org.springframework.http.HttpStatus.CREATED;

/**
 * Espelha o troço "Convites de utilizadores" e "Utilizadores & Perfis da
 * própria empresa" de backend/src/routes/companyRoutes.js +
 * backend/src/controllers/companyController.js.
 *
 * NÃO PORTADO (ver InviteService, javadoc): cadastro público de empresa
 * (`POST /register`), due diligence do Admin do Sistema, configuração ERP,
 * plano/dimensão, série fiscal, dados bancários, ficha completa da empresa
 * — nenhum destes tem chamador ainda no Java (ERP/documentos/plano entram
 * nos próximos itens do lote 2 do plano).
 */
@RestController
@RequestMapping("/api/companies")
public class InviteController {

    /** {@code role: z.enum([...])} de createInviteSchema — COMPANY_ADMIN/ADMIN_SISTEMA ficam de fora logo à entrada. */
    private static final List<String> PERFIS_CONVIDAVEIS = List.of("COMPRADOR", "FORNECEDOR", "FINANCEIRO");

    private final InviteService inviteService;
    private final AuditService auditService;

    public InviteController(InviteService inviteService, AuditService auditService) {
        this.inviteService = inviteService;
        this.auditService = auditService;
    }

    private String publicBaseUrl(HttpServletRequest req) {
        String host = req.getHeader("host");
        return req.getScheme() + "://" + (host != null ? host : req.getServerName());
    }

    // --- Público: resolução/aceitação (o token assinado é a autorização) --

    @GetMapping("/invite/{token}")
    public ResolvedInviteDto resolverConvite(@PathVariable String token) {
        return inviteService.resolver(token);
    }

    @PostMapping("/invite/{token}/accept")
    @ResponseStatus(CREATED)
    public CompanyUserDto aceitarConvite(@PathVariable String token, @RequestBody AcceptInviteRequest body, HttpServletRequest req) {
        if (body.password() == null || body.password().isBlank()) {
            throw new ValidationException("Indique uma senha.");
        }
        if (!Boolean.TRUE.equals(body.termsAccepted())) {
            throw new ValidationException("É necessário aceitar os Termos de Uso e a Política de Privacidade.");
        }
        CompanyUserDto user = inviteService.aceitar(token, body.name(), body.email(), body.password());
        // Quem aceita ainda não tem sessão: o ator é anónimo, mas a conta criada fica
        // identificada. companyId fica null tal como no Node — USER_SELECT (e o
        // seu espelho, CompanyUserDto) nunca incluiu companyId, por isso
        // `user.companyId` já era `undefined` também do lado do Node aqui.
        Actor anonimo = auditService.anonimoFrom(req);
        Actor actor = new Actor(user.id(), user.name(), null, null, anonimo.ip());
        auditService.recordSafe(new AuditService.Entry(actor, "CONVITE_ACEITE", "User", user.id(), user.email(),
                java.util.Map.of("perfil", user.role())));
        return user;
    }

    // --- Company Admin: convites e equipa da própria empresa --------------

    @GetMapping("/users")
    @RequireRole({COMPANY_ADMIN})
    public List<CompanyUserDto> listarUtilizadores() {
        return inviteService.listarUtilizadores(CurrentUserHolder.get().companyId());
    }

    @GetMapping("/invites")
    @RequireRole({COMPANY_ADMIN})
    public List<InviteDto> listarConvites() {
        return inviteService.listar(CurrentUserHolder.get().companyId());
    }

    @PostMapping("/invites")
    @ResponseStatus(CREATED)
    @RequireRole({COMPANY_ADMIN})
    public InviteCreatedDto criarConvite(@RequestBody(required = false) CreateInviteRequest body, HttpServletRequest req) {
        validarConvite(body == null ? new CreateInviteRequest(null, null, null) : body);
        CurrentUser user = CurrentUserHolder.get();
        return inviteService.criar(user.companyId(), body.role(), body.name(), body.email(), publicBaseUrl(req));
    }

    /**
     * Espelha {@code validate(createInviteSchema)} (companyRoutes.js): corre ANTES
     * do controller, reporta todos os campos de uma vez e responde 422 com o
     * {@code flatten()} do zod. A regra de negócio "este perfil não se convida
     * para este tipo de empresa" (400) fica onde estava, no InviteService.
     */
    private static void validarConvite(CreateInviteRequest body) {
        ErrosDeCampos erros = new ErrosDeCampos();
        if (body.role() == null) erros.adicionar("role", ErrosDeCampos.REQUIRED);
        else if (!PERFIS_CONVIDAVEIS.contains(body.role())) erros.adicionar("role", ErrosDeCampos.enumInvalido(PERFIS_CONVIDAVEIS, body.role()));
        // z.string().min(2, ...) conta o comprimento tal e qual, sem trim.
        if (body.name() == null) erros.adicionar("name", ErrosDeCampos.REQUIRED);
        else if (body.name().length() < 2) erros.adicionar("name", "Indique o nome do funcionário.");
        if (body.email() == null) erros.adicionar("email", ErrosDeCampos.REQUIRED);
        else if (!ErrosDeCampos.EMAIL.matcher(body.email()).matches()) erros.adicionar("email", "Indique um email válido.");
        erros.lancarSeHouver();
    }

    @PostMapping("/invites/{id}/resend")
    @RequireRole({COMPANY_ADMIN})
    public InviteDto reenviarConvite(@PathVariable String id, HttpServletRequest req) {
        return inviteService.reenviar(CurrentUserHolder.get().companyId(), id, publicBaseUrl(req));
    }

    @PostMapping("/invites/{id}/cancel")
    @RequireRole({COMPANY_ADMIN})
    public InviteDto cancelarConvite(@PathVariable String id) {
        return inviteService.cancelar(CurrentUserHolder.get().companyId(), id);
    }

    @PatchMapping("/users/{id}/activate")
    @RequireRole({COMPANY_ADMIN})
    public CompanyUserDto ativarUtilizador(@PathVariable String id, HttpServletRequest req) {
        CompanyUserDto user = inviteService.ativar(CurrentUserHolder.get().companyId(), id);
        registarAuditoria(req, "UTILIZADOR_ATIVADO", user.id(), user.email());
        return user;
    }

    @PatchMapping("/users/{id}/status")
    @RequireRole({COMPANY_ADMIN})
    public CompanyUserDto definirEstadoUtilizador(@PathVariable String id, @RequestBody SetUserStatusRequest body, HttpServletRequest req) {
        CurrentUser currentUser = CurrentUserHolder.get();
        boolean active = Boolean.TRUE.equals(body.active());
        CompanyUserDto user = inviteService.definirEstado(currentUser.companyId(), id, active, currentUser.id());
        registarAuditoria(req, user.active() ? "UTILIZADOR_DESBLOQUEADO" : "UTILIZADOR_BLOQUEADO", user.id(), user.email());
        return user;
    }

    @DeleteMapping("/users/{id}")
    @RequireRole({COMPANY_ADMIN})
    public Map<String, String> removerUtilizador(@PathVariable String id, HttpServletRequest req) {
        String removidoId = inviteService.remover(CurrentUserHolder.get().companyId(), id);
        // `entityRef: null` — o Node também só tem `{id}` aqui, nunca o email do removido.
        registarAuditoria(req, "UTILIZADOR_REMOVIDO", removidoId, null);
        return Map.of("id", removidoId);
    }

    private void registarAuditoria(HttpServletRequest req, String action, String userId, String userEmail) {
        Actor actor = auditService.actorFrom(CurrentUserHolder.get(), req);
        auditService.recordSafe(new AuditService.Entry(actor, action, "User", userId, userEmail, auditService.contextoFrom(req)));
    }
}
