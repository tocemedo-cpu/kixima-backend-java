package ao.kixima.porobo;

import ao.kixima.analytics.CategoryAnalyticsService;
import ao.kixima.audit.Actor;
import ao.kixima.audit.AuditService;
import ao.kixima.porobo.dto.PoRoboRegraDto;
import ao.kixima.porobo.dto.PoRoboRegraRequest;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static ao.kixima.security.PersonaRole.COMPANY_ADMIN;
import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.NO_CONTENT;

/**
 * Espelha poRoboRoutes.js — o Company Admin configura as regras do robot.
 * Todas as rotas de escrita exigem o add-on ATIVO (AddonService.assertAddon
 * nunca deixa passar sem ele, mesmo que alguém tente ir direto ao endpoint).
 */
@RestController
@RequestMapping("/api/po-robot")
public class PoRoboController {

    private final PoRoboService poRoboService;
    private final AuditService auditService;

    public PoRoboController(PoRoboService poRoboService, AuditService auditService) {
        this.poRoboService = poRoboService;
        this.auditService = auditService;
    }

    @GetMapping("/regras")
    @RequireRole({COMPANY_ADMIN})
    public List<PoRoboRegraDto> listar() {
        return poRoboService.listar(CurrentUserHolder.get().companyId());
    }

    /** Média mensal sugerida pela IA — o cliente vê isto antes de decidir aceitá-la ou definir a sua própria. */
    @GetMapping("/media-sugerida/{productId}")
    @RequireRole({COMPANY_ADMIN})
    public CategoryAnalyticsService.MediaMensal mediaSugerida(@PathVariable String productId) {
        return poRoboService.mediaSugerida(CurrentUserHolder.get().companyId(), productId);
    }

    @PostMapping("/regras")
    @ResponseStatus(CREATED)
    @RequireRole({COMPANY_ADMIN})
    public PoRoboRegraDto criar(@RequestBody PoRoboRegraRequest body, HttpServletRequest req) {
        return poRoboService.criar(CurrentUserHolder.get().companyId(), body, actor(req));
    }

    @PutMapping("/regras/{id}")
    @RequireRole({COMPANY_ADMIN})
    public PoRoboRegraDto atualizar(@PathVariable String id, @RequestBody PoRoboRegraRequest body, HttpServletRequest req) {
        return poRoboService.atualizar(CurrentUserHolder.get().companyId(), id, body, actor(req));
    }

    @DeleteMapping("/regras/{id}")
    @ResponseStatus(NO_CONTENT)
    @RequireRole({COMPANY_ADMIN})
    public void remover(@PathVariable String id, HttpServletRequest req) {
        poRoboService.remover(CurrentUserHolder.get().companyId(), id, actor(req));
    }

    private Actor actor(HttpServletRequest req) {
        return auditService.actorFrom(CurrentUserHolder.get(), req);
    }
}
