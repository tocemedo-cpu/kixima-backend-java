package ao.kixima.porobo;

import ao.kixima.addon.AddonService;
import ao.kixima.analytics.CategoryAnalyticsService;
import ao.kixima.audit.Actor;
import ao.kixima.audit.AuditService;
import ao.kixima.catalog.Product;
import ao.kixima.catalog.ProductRepository;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.common.error.ValidationException;
import ao.kixima.common.money.FxService;
import ao.kixima.ops.OperationalAlertService;
import ao.kixima.po.PoService;
import ao.kixima.po.PurchaseOrder;
import ao.kixima.porobo.dto.PoRoboRegraDto;
import ao.kixima.porobo.dto.PoRoboRegraRequest;
import ao.kixima.security.PersonaRole;
import ao.kixima.user.User;
import ao.kixima.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Espelha backend/src/services/poRoboService.js + poRoboRoutes.js —
 * Automatic PO Robot (add-on PRO, pago): PREPARA POs periodicamente, com base
 * na média mensal de compra por produto. NUNCA aprova nem paga: a PO nasce
 * em AGUARDANDO_APROVACAO como qualquer outra ({@link PoService#createPurchaseOrder}
 * é chamado TAL E QUAL, sem via paralela) — {@code createdBySource} só marca
 * a origem para a auditoria/interface distinguirem.
 *
 * Transações: cada regra corre na SUA transação (TransactionTemplate), tal
 * como no Node cada regra é independente — uma regra que falha não pode
 * arrastar consigo as POs já criadas pelas outras no mesmo ciclo.
 */
@Service
public class PoRoboService {

    private static final Logger log = LoggerFactory.getLogger(PoRoboService.class);
    private static final Actor ATOR_ROBOT = new Actor(null, "PO Robot", null, null, null);

    /** Janela da reserva de uma regra durante a execução — só tem de ser maior do que o tempo que uma execução demora. */
    static final long RESERVA_MS = 10 * 60 * 1000;

    private static final Map<PoRoboPeriodicidade, BigDecimal> FRACAO_DO_MES = Map.of(
            PoRoboPeriodicidade.SEMANAL, BigDecimal.valueOf(7).divide(BigDecimal.valueOf(30), 10, RoundingMode.HALF_UP),
            PoRoboPeriodicidade.QUINZENAL, BigDecimal.valueOf(14).divide(BigDecimal.valueOf(30), 10, RoundingMode.HALF_UP),
            PoRoboPeriodicidade.MENSAL, BigDecimal.ONE);
    private static final Map<PoRoboPeriodicidade, Integer> DIAS_ATE_PROXIMA = Map.of(
            PoRoboPeriodicidade.SEMANAL, 7, PoRoboPeriodicidade.QUINZENAL, 14, PoRoboPeriodicidade.MENSAL, 30);

    public record Falha(String regraId, String companyId, String erro) {
    }

    public record CicloResultado(int total, int criadas, List<Falha> falhas) {
    }

    private final PoRoboRegraRepository regraRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final PoService poService;
    private final AddonService addonService;
    private final CategoryAnalyticsService categoryAnalyticsService;
    private final FxService fxService;
    private final AuditService auditService;
    private final OperationalAlertService operationalAlertService;
    private final TransactionTemplate transactionTemplate;

    public PoRoboService(PoRoboRegraRepository regraRepository, ProductRepository productRepository,
                          UserRepository userRepository, PoService poService, AddonService addonService,
                          CategoryAnalyticsService categoryAnalyticsService, FxService fxService,
                          AuditService auditService, OperationalAlertService operationalAlertService,
                          PlatformTransactionManager transactionManager) {
        this.regraRepository = regraRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.poService = poService;
        this.addonService = addonService;
        this.categoryAnalyticsService = categoryAnalyticsService;
        this.fxService = fxService;
        this.auditService = auditService;
        this.operationalAlertService = operationalAlertService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // --- Execução (job) -------------------------------------------------------

    static Instant proximaExecucao(PoRoboPeriodicidade periodicidade, Instant base) {
        int dias = DIAS_ATE_PROXIMA.getOrDefault(periodicidade, 30);
        return base.plus(dias, ChronoUnit.DAYS);
    }

    /**
     * A quantidade a pedir NESTA execução — a média aceite/definida pelo
     * cliente, escalada para a periodicidade. {@code quantidade} (fixa)
     * sobrepõe o cálculo quando o cliente a definiu à mão.
     */
    public int resolverQuantidade(PoRoboRegra regra) {
        if (regra.getQuantidade() != null) return regra.getQuantidade();

        BigDecimal mediaMensal = regra.getMediaMensal();
        if (regra.getMediaOrigem() == PoRoboMediaOrigem.IA) {
            mediaMensal = categoryAnalyticsService.mediaMensalPorProduto(regra.getCompanyId(), regra.getProductId()).mediaMensal();
        }

        BigDecimal fracao = FRACAO_DO_MES.getOrDefault(regra.getPeriodicidade(), BigDecimal.ONE);
        return Math.max(1, mediaMensal.multiply(fracao).setScale(0, RoundingMode.HALF_UP).intValue());
    }

    /**
     * Uma regra, um ciclo: resolve a quantidade, valida o limite de segurança,
     * cria a PO (sempre por aprovar) e avança a próxima execução — só depois
     * do sucesso, para não duplicar se o job correr duas vezes seguidas.
     */
    @Transactional
    public PurchaseOrder executarRegra(PoRoboRegra regra) {
        addonService.assertAddon(regra.getCompanyId(), AddonService.PO_ROBOT, "Automatic PO Robot");

        Product product = productRepository.findById(regra.getProductId()).orElse(null);
        if (product == null || !product.isActive()) {
            throw new IllegalStateException("Produto " + regra.getProductId() + " não existe ou está inativo — regra desativada.");
        }

        int quantidade = resolverQuantidade(regra);
        BigDecimal valorEstimadoUsd = fxService.toUsd(product.getUnitPrice().multiply(BigDecimal.valueOf(quantidade)), product.getCurrency());

        if (regra.getLimiteMaximoUsd() != null && valorEstimadoUsd.compareTo(regra.getLimiteMaximoUsd()) > 0) {
            throw new IllegalStateException("PO estimada em " + valorEstimadoUsd.toPlainString() + " USD excede o limite de segurança de "
                    + regra.getLimiteMaximoUsd().stripTrailingZeros().toPlainString() + " USD — não criada.");
        }

        // O robot atribui a PO ao Company Admin mais antigo da empresa (a PO precisa de um
        // autor humano válido — o FK não abre exceção nenhuma para "sistema").
        User admin = userRepository.findFirstByCompanyIdAndRoleAndActiveTrueOrderByCreatedAtAsc(
                regra.getCompanyId(), PersonaRole.COMPANY_ADMIN).orElse(null);
        if (admin == null) {
            throw new IllegalStateException("Nenhum Company Admin ativo nesta empresa — o robot não tem em nome de quem criar a PO.");
        }

        PurchaseOrder po = poService.createPurchaseOrder(regra.getCompanyId(), product.getSupplierId(), admin.getId(),
                List.of(new PoService.ItemPedido(product.getId(), quantidade)), "ROBOT");

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("regraId", regra.getId());
        detail.put("produto", product.getName());
        detail.put("quantidade", quantidade);
        detail.put("valorEstimadoUsd", valorEstimadoUsd.toPlainString());
        detail.put("mediaOrigem", regra.getMediaOrigem().name());
        auditService.recordSafe(new AuditService.Entry(ATOR_ROBOT, "PO_CRIADA_ROBOT", "PurchaseOrder", po.getId(),
                po.getReference(), detail));

        regraRepository.definirProximaExecucao(regra.getId(), proximaExecucao(regra.getPeriodicidade(), Instant.now()));
        return po;
    }

    /**
     * Corre todas as regras ativas cuja vez chegou. Uma regra que falha
     * (produto descontinuado, limite excedido, empresa sem add-on ativo…) NÃO
     * aborta o ciclo inteiro — regista o erro e segue para a regra seguinte.
     */
    public CicloResultado executarCiclo() {
        List<PoRoboRegra> regras = transactionTemplate.execute(status ->
                regraRepository.findByAtivoTrueAndProximaExecucaoEmLessThanEqual(Instant.now()));

        int criadas = 0;
        List<Falha> falhas = new ArrayList<>();

        for (PoRoboRegra regra : regras) {
            Instant lida = regra.getProximaExecucaoEm();
            Integer reservadas = transactionTemplate.execute(status ->
                    regraRepository.reclamar(regra.getId(), lida, Instant.now().plusMillis(RESERVA_MS)));
            if (reservadas == null || reservadas != 1) continue; // outra corrida já reclamou esta regra

            try {
                transactionTemplate.executeWithoutResult(status -> executarRegra(regra));
                criadas++;
            } catch (Exception err) {
                // Falhou depois de reservada — repõe a data original para o ciclo seguinte tentar de novo.
                try {
                    transactionTemplate.executeWithoutResult(status -> regraRepository.definirProximaExecucao(regra.getId(), lida));
                } catch (Exception ignorado) {
                    // mesmo `.catch(() => {})` do Node — a falha original é a que importa
                }
                falhas.add(new Falha(regra.getId(), regra.getCompanyId(), err.getMessage()));
                log.warn("poRoboService: falha ao executar regra {}: {}", regra.getId(), err.getMessage());
                operationalAlertService.avisarFalha("PO_ROBOT", "Falha ao criar PO automática (regra " + regra.getId() + ")",
                        err.getMessage());
            }
        }

        return new CicloResultado(regras.size(), criadas, falhas);
    }

    // --- Configuração das regras (poRoboRoutes.js) -----------------------------

    @Transactional(readOnly = true)
    public List<PoRoboRegraDto> listar(String companyId) {
        return regraRepository.findByCompanyIdComProduto(companyId).stream().map(r -> PoRoboRegraDto.from(r, true)).toList();
    }

    @Transactional(readOnly = true)
    public CategoryAnalyticsService.MediaMensal mediaSugerida(String companyId, String productId) {
        addonService.assertAddon(companyId, AddonService.PO_ROBOT);
        return categoryAnalyticsService.mediaMensalPorProduto(companyId, productId);
    }

    private record CorpoValidado(String productId, PoRoboMediaOrigem mediaOrigem, BigDecimal mediaMensal,
                                 PoRoboPeriodicidade periodicidade, Integer quantidade, BigDecimal limiteMaximoUsd) {
    }

    private static PoRoboMediaOrigem parseOrigem(String v) {
        try {
            return v == null ? null : PoRoboMediaOrigem.valueOf(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static PoRoboPeriodicidade parsePeriodicidade(String v) {
        try {
            return v == null ? null : PoRoboPeriodicidade.valueOf(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean presente(JsonNode n) {
        return n != null; // JSON null vem como NullNode (presente e a limpar); ausente vem como null Java
    }

    private CorpoValidado validarCorpo(String productId, PoRoboMediaOrigem mediaOrigem, BigDecimal mediaMensal,
                                       PoRoboPeriodicidade periodicidade, JsonNode quantidade, JsonNode limiteMaximoUsd) {
        if (productId == null || productId.isBlank()) throw new ValidationException("Indique o produto.");
        if (mediaOrigem == null) throw new ValidationException("mediaOrigem inválida — use IA ou MANUAL.");
        if (mediaMensal == null || mediaMensal.signum() <= 0) throw new ValidationException("mediaMensal tem de ser maior que zero.");
        if (periodicidade == null) throw new ValidationException("periodicidade inválida — use SEMANAL, QUINZENAL, MENSAL.");

        Integer qtd = null;
        if (quantidade != null && !quantidade.isNull()) {
            if (!quantidade.canConvertToInt() || quantidade.asInt() <= 0 || (quantidade.isNumber() && !quantidade.isIntegralNumber())) {
                throw new ValidationException("quantidade tem de ser um número inteiro maior que zero.");
            }
            qtd = quantidade.asInt();
        }
        BigDecimal limite = null;
        if (limiteMaximoUsd != null && !limiteMaximoUsd.isNull()) {
            if (!limiteMaximoUsd.isNumber() || limiteMaximoUsd.decimalValue().signum() <= 0) {
                throw new ValidationException("limiteMaximoUsd tem de ser maior que zero.");
            }
            limite = limiteMaximoUsd.decimalValue();
        }
        return new CorpoValidado(productId, mediaOrigem, mediaMensal, periodicidade, qtd, limite);
    }

    @Transactional
    public PoRoboRegraDto criar(String companyId, PoRoboRegraRequest body, Actor actor) {
        addonService.assertAddon(companyId, AddonService.PO_ROBOT);
        CorpoValidado dados = validarCorpo(body.productId(), parseOrigem(body.mediaOrigem()), body.mediaMensal(),
                parsePeriodicidade(body.periodicidade()), body.quantidade(), body.limiteMaximoUsd());

        Product produto = productRepository.findById(dados.productId()).orElseThrow(() -> new NotFoundException("Produto"));

        // Elegível já na próxima corrida do job — a periodicidade rege o INTERVALO entre execuções, não um atraso antes da primeira.
        PoRoboRegra regra = new PoRoboRegra(UUID.randomUUID().toString(), companyId, dados.productId(), dados.mediaOrigem(),
                dados.mediaMensal(), dados.periodicidade(), dados.quantidade(), dados.limiteMaximoUsd(), Instant.now(), Instant.now());
        regraRepository.save(regra);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("produto", produto.getName());
        detail.put("productId", dados.productId());
        detail.put("mediaOrigem", dados.mediaOrigem().name());
        detail.put("mediaMensal", dados.mediaMensal());
        detail.put("periodicidade", dados.periodicidade().name());
        detail.put("quantidade", dados.quantidade());
        detail.put("limiteMaximoUsd", dados.limiteMaximoUsd());
        auditService.recordSafe(new AuditService.Entry(actor, "PO_ROBO_REGRA_CRIADA", "PoRoboRegra", regra.getId(), null, detail));

        return PoRoboRegraDto.from(regra, false);
    }

    @Transactional
    public PoRoboRegraDto atualizar(String companyId, String id, PoRoboRegraRequest body, Actor actor) {
        addonService.assertAddon(companyId, AddonService.PO_ROBOT);
        PoRoboRegra existente = regraRepository.findById(id).orElse(null);
        if (existente == null || !existente.getCompanyId().equals(companyId)) throw new NotFoundException("Regra");

        Map<String, Object> data = new LinkedHashMap<>();
        if (body.ativo() != null) {
            existente.setAtivo(body.ativo());
            data.put("ativo", body.ativo());
        }
        boolean mexeNaRegra = body.mediaOrigem() != null || body.mediaMensal() != null || body.periodicidade() != null
                || presente(body.quantidade()) || presente(body.limiteMaximoUsd());
        if (mexeNaRegra) {
            JsonNode quantidade = presente(body.quantidade()) ? body.quantidade()
                    : (existente.getQuantidade() == null ? null : com.fasterxml.jackson.databind.node.IntNode.valueOf(existente.getQuantidade()));
            JsonNode limite = presente(body.limiteMaximoUsd()) ? body.limiteMaximoUsd()
                    : (existente.getLimiteMaximoUsd() == null ? null : com.fasterxml.jackson.databind.node.DecimalNode.valueOf(existente.getLimiteMaximoUsd()));
            CorpoValidado validado = validarCorpo(existente.getProductId(),
                    body.mediaOrigem() != null ? parseOrigem(body.mediaOrigem()) : existente.getMediaOrigem(),
                    body.mediaMensal() != null ? body.mediaMensal() : existente.getMediaMensal(),
                    body.periodicidade() != null ? parsePeriodicidade(body.periodicidade()) : existente.getPeriodicidade(),
                    quantidade, limite);
            existente.setMediaOrigem(validado.mediaOrigem());
            existente.setMediaMensal(validado.mediaMensal());
            existente.setPeriodicidade(validado.periodicidade());
            existente.setQuantidade(validado.quantidade());
            existente.setLimiteMaximoUsd(validado.limiteMaximoUsd());
            data.put("mediaOrigem", validado.mediaOrigem().name());
            data.put("mediaMensal", validado.mediaMensal());
            data.put("periodicidade", validado.periodicidade().name());
            data.put("quantidade", validado.quantidade());
            data.put("limiteMaximoUsd", validado.limiteMaximoUsd());
        }

        auditService.recordSafe(new AuditService.Entry(actor, "PO_ROBO_REGRA_ATUALIZADA", "PoRoboRegra", existente.getId(), null, data));
        return PoRoboRegraDto.from(existente, false);
    }

    @Transactional
    public void remover(String companyId, String id, Actor actor) {
        PoRoboRegra existente = regraRepository.findById(id).orElse(null);
        if (existente == null || !existente.getCompanyId().equals(companyId)) throw new NotFoundException("Regra");
        regraRepository.delete(existente);
        auditService.recordSafe(new AuditService.Entry(actor, "PO_ROBO_REGRA_REMOVIDA", "PoRoboRegra", existente.getId(), null, null));
    }
}
