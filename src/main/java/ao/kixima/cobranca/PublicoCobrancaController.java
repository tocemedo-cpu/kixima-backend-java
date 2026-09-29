package ao.kixima.cobranca;

import ao.kixima.common.Decimais;

import ao.kixima.common.error.ErrorResponse;
import ao.kixima.payment.PlatformFeeService;
import ao.kixima.plan.PlanService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * As duas rotas PÚBLICAS da cobrança: a tabela de planos (planosRoutes.js) e
 * o callback dos canais de pagamento automático (webhookPagamentoRoutes.js).
 * O webhook fica fora da sessão de propósito — quem chama é o gateway; a
 * segurança vem de cada adaptador voltar sempre a perguntar ao gateway pela
 * transação em vez de acreditar no corpo do pedido.
 */
@RestController
public class PublicoCobrancaController {

    private final PlanService planService;
    private final PlatformFeeService platformFeeService;
    private final CanaisPagamentoService canaisPagamentoService;
    private final PlanoCobrancaRepository cobrancaRepository;
    private final AssinaturaService assinaturaService;

    public PublicoCobrancaController(PlanService planService, PlatformFeeService platformFeeService, CanaisPagamentoService canaisPagamentoService,
                                     PlanoCobrancaRepository cobrancaRepository, AssinaturaService assinaturaService) {
        this.planService = planService;
        this.platformFeeService = platformFeeService;
        this.canaisPagamentoService = canaisPagamentoService;
        this.cobrancaRepository = cobrancaRepository;
        this.assinaturaService = assinaturaService;
    }

    /** Tabela de planos e preços — para a página de preços não ter os números escritos à mão. */
    @GetMapping("/api/planos")
    public Map<String, Object> planos() {
        Map<String, Object> taxa = new LinkedHashMap<>();
        taxa.put("porOrdemUsd", Decimais.numero(platformFeeService.perPo()));
        taxa.put("porFaturaUsd", Decimais.numero(platformFeeService.perInvoice()));
        taxa.put("limiarUsd", Decimais.numero(platformFeeService.thresholdUsd()));
        taxa.put("percentagemAcima", Decimais.numero(platformFeeService.percentAbove()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planos", planService.tabela());
        // Publicado junto: o que se paga por transação é a outra metade do modelo.
        out.put("taxaPorTransacao", taxa);
        return out;
    }

    @PostMapping("/api/webhooks/pagamento/{canal}")
    public ResponseEntity<?> webhook(@PathVariable String canal, @RequestBody(required = false) Map<String, Object> body) {
        String nome = canal == null ? "" : canal.toUpperCase();
        GatewayAdapter adaptador = canaisPagamentoService.adaptador(nome);
        if (adaptador == null) {
            return ResponseEntity.status(404).body(ErrorResponse.of("CANAL_DESCONHECIDO", "Canal desconhecido: " + nome + "."));
        }
        GatewayAdapter.Verificacao verificado = adaptador.confirmarCallback(body == null ? Map.of() : body);
        // Não pago — 200 para o gateway não reenviar em loop; não há cobrança para atualizar.
        if (!verificado.pago()) return ResponseEntity.ok(Map.of("recebido", true));

        CanalCobranca canalEnum = CanalCobranca.valueOf(nome);
        PlanoCobranca cobranca = cobrancaRepository.findFirstByCanalAndReferenciaExterna(canalEnum, verificado.idTransacao()).orElse(null);
        if (cobranca == null) {
            return ResponseEntity.status(404).body(ErrorResponse.of("COBRANCA_NAO_ENCONTRADA",
                    "Nenhuma cobrança de subscrição corresponde à transação " + verificado.idTransacao() + "."));
        }
        assinaturaService.confirmarViaGateway(cobranca.getId(), canalEnum, verificado.idTransacao());
        return ResponseEntity.ok(Map.of("recebido", true));
    }
}
