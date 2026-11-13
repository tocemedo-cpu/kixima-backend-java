package ao.kixima.porobo;

import ao.kixima.audit.Actor;
import ao.kixima.audit.AuditService;
import ao.kixima.porobo.dto.PoRoboActividadeDto;
import ao.kixima.porobo.dto.PoRoboAjusteRequest;
import ao.kixima.porobo.dto.PoRoboExecucaoDto;
import ao.kixima.porobo.dto.PoRoboMediaSugeridaDto;
import ao.kixima.porobo.dto.PoRoboPrevisaoDto;
import ao.kixima.porobo.dto.PoRoboPropostaDto;
import ao.kixima.porobo.dto.PoRoboRegraDto;
import ao.kixima.porobo.dto.PoRoboRegraRequest;
import ao.kixima.porobo.dto.PoRoboRejeicaoRequest;
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
import static ao.kixima.security.PersonaRole.COMPRADOR;
import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.NO_CONTENT;

/**
 * Espelha poRoboRoutes.js + o contrato do frontend (docs/api-po-robot.md). O COMPRADOR e o Company Admin gerem as
 * regras e consultam previsões, propostas, execuções e actividades — sempre só da SUA empresa ({@code companyId} da
 * sessão). Todas as rotas de escrita exigem o add-on ATIVO (AddonService.assertAddon nunca deixa passar sem ele, mesmo
 * que alguém tente ir direto ao endpoint).
 *
 * <p>O robot PREPARA propostas; as execuções são agendadas pelo servidor (não há endpoint para as lançar). Submeter uma
 * proposta cria a PO em AGUARDANDO_APROVACAO. Não existe aqui nenhum endpoint de aprovação nem de pagamento — e não deve haver.
 */
@RestController
@RequestMapping("/api/po-robot")
public class PoRoboController {

    private final PoRoboService poRoboService;
    private final PoRoboPropostaService propostaService;
    private final PoRoboHistoricoService historicoService;
    private final AuditService auditService;

    public PoRoboController(PoRoboService poRoboService, PoRoboPropostaService propostaService,
                            PoRoboHistoricoService historicoService, AuditService auditService) {
        this.poRoboService = poRoboService;
        this.propostaService = propostaService;
        this.historicoService = historicoService;
        this.auditService = auditService;
    }

    // --- Regras ---------------------------------------------------------------

    @GetMapping("/regras")
    @RequireRole({COMPRADOR, COMPANY_ADMIN})
    public List<PoRoboRegraDto> listar() {
        return poRoboService.listar(empresa());
    }

    /** Média mensal sugerida pela IA — o cliente vê isto antes de decidir aceitá-la ou definir a sua própria. */
    @GetMapping("/media-sugerida/{productId}")
    @RequireRole({COMPRADOR, COMPANY_ADMIN})
    public PoRoboMediaSugeridaDto mediaSugerida(@PathVariable String productId) {
        return poRoboService.mediaSugerida(empresa(), productId);
    }

    @PostMapping("/regras")
    @ResponseStatus(CREATED)
    @RequireRole({COMPRADOR, COMPANY_ADMIN})
    public PoRoboRegraDto criar(@RequestBody PoRoboRegraRequest body, HttpServletRequest req) {
        return poRoboService.criar(empresa(), body, actor(req));
    }

    @PutMapping("/regras/{id}")
    @RequireRole({COMPRADOR, COMPANY_ADMIN})
    public PoRoboRegraDto atualizar(@PathVariable String id, @RequestBody PoRoboRegraRequest body, HttpServletRequest req) {
        return poRoboService.atualizar(empresa(), id, body, actor(req));
    }

    @DeleteMapping("/regras/{id}")
    @ResponseStatus(NO_CONTENT)
    @RequireRole({COMPRADOR, COMPANY_ADMIN})
    public void remover(@PathVariable String id, HttpServletRequest req) {
        poRoboService.remover(empresa(), id, actor(req));
    }

    // --- Previsões, propostas, execuções e actividade ---------------------------

    /** A previsão mais recente de cada regra. */
    @GetMapping("/previsoes")
    @RequireRole({COMPRADOR, COMPANY_ADMIN})
    public List<PoRoboPrevisaoDto> previsoes() {
        return historicoService.previsoes(empresa());
    }

    @GetMapping("/propostas")
    @RequireRole({COMPRADOR, COMPANY_ADMIN})
    public List<PoRoboPropostaDto> propostas() {
        return propostaService.listar(empresa());
    }

    /** Ajuste humano da quantidade — só em PENDENTE_REVISAO (senão 409). */
    @PutMapping("/propostas/{id}")
    @RequireRole({COMPRADOR, COMPANY_ADMIN})
    public PoRoboPropostaDto ajustar(@PathVariable String id, @RequestBody PoRoboAjusteRequest body, HttpServletRequest req) {
        return propostaService.ajustar(empresa(), id, body == null ? null : body.quantidade(), actor(req));
    }

    /** Envia para APROVAÇÃO HUMANA: cria a PO em AGUARDANDO_APROVACAO. Não aprova nem paga. */
    @PostMapping("/propostas/{id}/submeter")
    @RequireRole({COMPRADOR, COMPANY_ADMIN})
    public PoRoboPropostaDto submeter(@PathVariable String id, HttpServletRequest req) {
        return propostaService.submeter(empresa(), id, actor(req));
    }

    @PostMapping("/propostas/{id}/rejeitar")
    @RequireRole({COMPRADOR, COMPANY_ADMIN})
    public PoRoboPropostaDto rejeitar(@PathVariable String id, @RequestBody PoRoboRejeicaoRequest body, HttpServletRequest req) {
        return propostaService.rejeitar(empresa(), id, body == null ? null : body.motivo(), actor(req));
    }

    @GetMapping("/execucoes")
    @RequireRole({COMPRADOR, COMPANY_ADMIN})
    public List<PoRoboExecucaoDto> execucoes() {
        return historicoService.execucoes(empresa());
    }

    @GetMapping("/actividades")
    @RequireRole({COMPRADOR, COMPANY_ADMIN})
    public List<PoRoboActividadeDto> actividades() {
        return historicoService.actividades(empresa());
    }

    private String empresa() {
        return CurrentUserHolder.get().companyId();
    }

    private Actor actor(HttpServletRequest req) {
        return auditService.actorFrom(CurrentUserHolder.get(), req);
    }
}
