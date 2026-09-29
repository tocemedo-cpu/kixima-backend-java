package ao.kixima.conciliacao;

import ao.kixima.common.pagination.PaginaResposta;
import ao.kixima.conciliacao.dto.LinhaExtratoDto;
import ao.kixima.conciliacao.dto.LinhaExtratoInput;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.RequirePermission;
import ao.kixima.security.RequireRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static ao.kixima.security.AdminArea.FINANCEIRO;
import static ao.kixima.security.PersonaRole.ADMIN_SISTEMA;

/** Espelha conciliacaoRoutes.js — conciliação do extrato bancário. Só o Financeiro e o Admin do Sistema (área Financeiro). */
@RestController
@RequestMapping("/api/conciliacao")
public class ConciliacaoController {

    public record ExtratoRequest(List<LinhaExtratoInput> linhas) {
    }

    public record ConciliarRequest(String referencia) {
    }

    private final ConciliacaoService conciliacaoService;
    private final MulticaixaService multicaixaService;

    public ConciliacaoController(ConciliacaoService conciliacaoService, MulticaixaService multicaixaService) {
        this.conciliacaoService = conciliacaoService;
        this.multicaixaService = multicaixaService;
    }

    /** Importa linhas do extrato e concilia o que casar. */
    @PostMapping("/extrato")
    @RequireRole({ao.kixima.security.PersonaRole.FINANCEIRO, ADMIN_SISTEMA})
    @RequirePermission(FINANCEIRO)
    public ConciliacaoService.ResultadoImportacao importarExtrato(@RequestBody(required = false) ExtratoRequest body) {
        List<LinhaExtratoInput> linhas = body == null || body.linhas() == null ? List.of() : body.linhas();
        return conciliacaoService.importarExtrato(linhas, CurrentUserHolder.get());
    }

    /** O que sobrou para uma pessoa resolver. */
    @GetMapping("/por-resolver")
    @RequireRole({ao.kixima.security.PersonaRole.FINANCEIRO, ADMIN_SISTEMA})
    @RequirePermission(FINANCEIRO)
    public PaginaResposta<LinhaExtratoDto> porResolver(@RequestParam(required = false) Integer page,
                                                       @RequestParam(required = false) Integer limit) {
        return conciliacaoService.porResolver(page, limit);
    }

    @PostMapping("/{id}/conciliar")
    @RequireRole({ao.kixima.security.PersonaRole.FINANCEIRO, ADMIN_SISTEMA})
    @RequirePermission(FINANCEIRO)
    public ConciliacaoService.Desfecho conciliar(@PathVariable String id, @RequestBody(required = false) ConciliarRequest body) {
        return conciliacaoService.reconciliarManualmente(id, body == null ? null : body.referencia(), CurrentUserHolder.get());
    }

    /** Estado dos canais automáticos, para não se descobrir que um não está ligado quando alguém carrega no botão. */
    @GetMapping("/canais")
    @RequireRole({ao.kixima.security.PersonaRole.FINANCEIRO, ADMIN_SISTEMA})
    @RequirePermission(FINANCEIRO)
    public Map<String, Object> canais() {
        List<Map<String, Object>> canais = new ArrayList<>();
        canais.add(Map.of("canal", "TRANSFERENCIA_MANUAL", "disponivel", true, "nota", "Comprovativo carregado e confirmado por uma pessoa."));
        canais.add(Map.of("canal", "REFERENCIA_BANCARIA", "disponivel", true, "nota", "Referência única por fatura, conciliada pelo extrato."));
        canais.add(multicaixaService.estado());
        return Map.of("canais", canais);
    }
}
