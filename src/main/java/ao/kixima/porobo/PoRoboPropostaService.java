package ao.kixima.porobo;

import ao.kixima.addon.AddonService;
import ao.kixima.audit.Actor;
import ao.kixima.audit.AuditService;
import ao.kixima.catalog.Product;
import ao.kixima.catalog.ProductRepository;
import ao.kixima.common.error.BusinessRuleException;
import ao.kixima.common.error.ConflictException;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.common.error.ValidationException;
import ao.kixima.common.money.FxService;
import ao.kixima.po.PoService;
import ao.kixima.po.PurchaseOrder;
import ao.kixima.porobo.dto.PoRoboPropostaDto;
import ao.kixima.porobo.dto.PoRoboRegraDto;
import ao.kixima.user.User;
import ao.kixima.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decisões humanas sobre as propostas do PO Robot: ajustar a quantidade, submeter (cria a PO) e rejeitar.
 *
 * <p>Regras de segurança funcional: o robot nunca decide — aqui só entram pessoas autenticadas da MESMA empresa
 * ({@code companyId} da sessão em todas as consultas). Submeter cria a PO pelo {@code PoService} (sem via paralela), em
 * AGUARDANDO_APROVACAO, em nome de quem submeteu; a aprovação e o pagamento seguem o fluxo normal da empresa. Não há,
 * neste módulo, aprovação nem pagamento.
 */
@Service
public class PoRoboPropostaService {

    private static final int MAX_MOTIVO = 500;

    private final PoRoboPropostaRepository propostaRepository;
    private final PoRoboRegraRepository regraRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final PoService poService;
    private final AddonService addonService;
    private final FxService fxService;
    private final AuditService auditService;

    public PoRoboPropostaService(PoRoboPropostaRepository propostaRepository, PoRoboRegraRepository regraRepository,
                                 ProductRepository productRepository, UserRepository userRepository, PoService poService,
                                 AddonService addonService, FxService fxService, AuditService auditService) {
        this.propostaRepository = propostaRepository;
        this.regraRepository = regraRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.poService = poService;
        this.addonService = addonService;
        this.fxService = fxService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<PoRoboPropostaDto> listar(String companyId) {
        List<PoRoboProposta> propostas = propostaRepository.listarDaEmpresa(companyId);
        Map<String, PoRoboRegraDto.Ref> revisores = revisores(propostas);
        return propostas.stream().map(p -> paraDto(p, revisores)).toList();
    }

    @Transactional
    public PoRoboPropostaDto ajustar(String companyId, String id, JsonNode quantidade, Actor actor) {
        addonService.assertAddon(companyId, AddonService.PO_ROBOT);
        PoRoboProposta proposta = daEmpresa(companyId, id);
        exigirPendente(proposta, "ajustada");

        int nova = quantidadeValida(quantidade);
        verificarLimite(proposta, nova);

        int antes = proposta.getQuantidade();
        proposta.ajustarQuantidade(nova);
        propostaRepository.save(proposta);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("produto", nomeDoProduto(proposta));
        detail.put("de", antes);
        detail.put("para", nova);
        auditService.recordSafe(new AuditService.Entry(actor, PoRoboAuditoria.PROPOSTA_AJUSTADA, PoRoboAuditoria.ENTIDADE_PROPOSTA,
                proposta.getId(), nomeDoProduto(proposta), detail));
        return paraDto(proposta);
    }

    /**
     * Submeter = decisão humana: cria a PO em AGUARDANDO_APROVACAO em nome de quem submete. A reserva atómica do estado
     * impede duas POs se dois pedidos chegarem ao mesmo tempo; se a criação da PO falhar (por exemplo, stock do
     * fornecedor insuficiente), tudo reverte e a proposta continua pendente.
     */
    @Transactional
    public PoRoboPropostaDto submeter(String companyId, String id, Actor actor) {
        addonService.assertAddon(companyId, AddonService.PO_ROBOT);
        PoRoboProposta proposta = daEmpresa(companyId, id);
        exigirPendente(proposta, "submetida");

        Product produto = productRepository.findById(proposta.getProductId()).orElse(null);
        if (produto == null || !produto.isActive()) {
            throw new BusinessRuleException("O produto desta proposta já não está disponível — rejeite a proposta.");
        }

        if (propostaRepository.reservarDecisao(proposta.getId(), companyId, EstadoProposta.SUBMETIDA) != 1) {
            throw new ConflictException("A proposta já foi decidida por outra pessoa.");
        }

        PurchaseOrder po = poService.createPurchaseOrder(companyId, produto.getSupplierId(), actor.actorId(),
                List.of(new PoService.ItemPedido(produto.getId(), proposta.getQuantidade())), "ROBOT");

        PoRoboProposta actual = propostaRepository.findByIdAndCompanyId(id, companyId).orElseThrow();
        actual.marcarSubmetida(po.getId(), po.getReference(), actor.actorId(), Instant.now());
        propostaRepository.save(actual);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("produto", produto.getName());
        detail.put("quantidade", actual.getQuantidade());
        detail.put("purchaseOrderId", po.getId());
        detail.put("purchaseOrderReference", po.getReference());
        auditService.record(new AuditService.Entry(actor, PoRoboAuditoria.PROPOSTA_SUBMETIDA, PoRoboAuditoria.ENTIDADE_PROPOSTA,
                actual.getId(), po.getReference(), detail));
        return paraDto(actual);
    }

    @Transactional
    public PoRoboPropostaDto rejeitar(String companyId, String id, String motivo, Actor actor) {
        addonService.assertAddon(companyId, AddonService.PO_ROBOT);
        String texto = motivo == null ? "" : motivo.trim();
        if (texto.isEmpty()) throw new ValidationException("Indique o motivo da rejeição.");
        if (texto.length() > MAX_MOTIVO) throw new ValidationException("O motivo da rejeição não pode passar de " + MAX_MOTIVO + " caracteres.");

        PoRoboProposta proposta = daEmpresa(companyId, id);
        exigirPendente(proposta, "rejeitada");
        if (propostaRepository.reservarDecisao(proposta.getId(), companyId, EstadoProposta.REJEITADA) != 1) {
            throw new ConflictException("A proposta já foi decidida por outra pessoa.");
        }

        PoRoboProposta actual = propostaRepository.findByIdAndCompanyId(id, companyId).orElseThrow();
        actual.rejeitar(texto, actor.actorId(), Instant.now());
        propostaRepository.save(actual);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("produto", nomeDoProduto(actual));
        detail.put("quantidade", actual.getQuantidade());
        detail.put("motivo", texto);
        auditService.recordSafe(new AuditService.Entry(actor, PoRoboAuditoria.PROPOSTA_REJEITADA, PoRoboAuditoria.ENTIDADE_PROPOSTA,
                actual.getId(), nomeDoProduto(actual), detail));
        return paraDto(actual);
    }

    // --- internos -------------------------------------------------------------

    /** 404 para propostas de outra empresa — nunca se revela que existem. */
    private PoRoboProposta daEmpresa(String companyId, String id) {
        return propostaRepository.findByIdAndCompanyId(id, companyId).orElseThrow(() -> new NotFoundException("Proposta"));
    }

    private static void exigirPendente(PoRoboProposta p, String acao) {
        if (!p.pendente()) {
            throw new ConflictException("A proposta não pode ser " + acao + ": está " + p.getEstado().name() + ".");
        }
    }

    private static int quantidadeValida(JsonNode n) {
        if (n == null || n.isNull() || !n.isNumber() || !n.isIntegralNumber() || !n.canConvertToInt() || n.asInt() <= 0) {
            throw new ValidationException("quantidade tem de ser um número inteiro maior que zero.");
        }
        return n.asInt();
    }

    /** O limite de segurança da regra também vale para o ajuste humano: o robot nunca propõe acima dele. */
    private void verificarLimite(PoRoboProposta proposta, int quantidade) {
        if (proposta.getRegraId() == null) return;
        PoRoboRegra regra = regraRepository.findById(proposta.getRegraId()).orElse(null);
        if (regra == null || regra.getLimiteMaximoUsd() == null || proposta.getPrecoUnitario() == null) return;
        BigDecimal valor = fxService.toUsd(proposta.getPrecoUnitario().multiply(BigDecimal.valueOf(quantidade)), proposta.getMoeda());
        if (valor.compareTo(regra.getLimiteMaximoUsd()) > 0) {
            throw new ValidationException("A quantidade " + quantidade + " custaria " + valor.toPlainString() + " USD e ultrapassa o limite de segurança de "
                    + regra.getLimiteMaximoUsd().stripTrailingZeros().toPlainString() + " USD da regra.");
        }
    }

    private String nomeDoProduto(PoRoboProposta p) {
        return productRepository.findById(p.getProductId()).map(Product::getName).orElse(p.getProductId());
    }

    private PoRoboPropostaDto paraDto(PoRoboProposta p) {
        return paraDto(p, revisores(List.of(p)));
    }

    private PoRoboPropostaDto paraDto(PoRoboProposta p, Map<String, PoRoboRegraDto.Ref> revisores) {
        // O produto (e a fornecedora) são relações lazy: carregam-se aqui, dentro da transacção do chamador.
        return PoRoboPropostaDto.de(p, PoRoboJson.lerPassos(p.getJustificacao()),
                p.getRevistaPorId() == null ? null : revisores.get(p.getRevistaPorId()));
    }

    private Map<String, PoRoboRegraDto.Ref> revisores(List<PoRoboProposta> propostas) {
        Set<String> ids = new HashSet<>();
        for (PoRoboProposta p : propostas) if (p.getRevistaPorId() != null) ids.add(p.getRevistaPorId());
        if (ids.isEmpty()) return Map.of();
        return userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, u -> new PoRoboRegraDto.Ref(u.getId(), u.getName()), (a, b) -> a));
    }
}
