package ao.kixima.report;

import ao.kixima.common.Decimais;

import ao.kixima.catalog.Product;
import ao.kixima.catalog.ProductRepository;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyPlan;
import ao.kixima.company.CompanyRepository;
import ao.kixima.plan.PlanLimit;
import ao.kixima.plan.PlanService;
import ao.kixima.po.PoStatus;
import ao.kixima.po.PurchaseOrder;
import ao.kixima.po.PurchaseOrderItem;
import ao.kixima.po.PurchaseOrderItemRepository;
import ao.kixima.po.PurchaseOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Espelha reportsService.js — relatórios/estatísticas do Fornecedor a partir de dados reais (catálogo + POs). */
@Service
public class ReportsService {

    /** POs cujo pagamento já entrou (receita reconhecida para o fornecedor). */
    static final Set<PoStatus> PAID_STATUSES = Set.of(PoStatus.PAGA, PoStatus.EM_EXECUCAO, PoStatus.ENTREGUE,
            PoStatus.RECEBIDA_CONFORME, PoStatus.RECEBIDA_COM_DIVERGENCIA, PoStatus.CONCLUIDA);

    /**
     * A janela de histórico que este relatório pode mostrar: 3 meses no BASE,
     * 12 no CORE e tudo no Pro. Quem pede menos do que o plano permite recebe
     * o que pediu; quem pede mais recebe o que o plano dá — e o relatório DIZ
     * qual foi a janela aplicada.
     */
    public record Janela(Integer meses, boolean ilimitado, Integer limitePlano, boolean truncada) {
    }

    private final PlanService planService;
    private final CompanyRepository companyRepository;
    private final ProductRepository productRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;

    public ReportsService(PlanService planService, CompanyRepository companyRepository, ProductRepository productRepository,
                          PurchaseOrderRepository purchaseOrderRepository, PurchaseOrderItemRepository purchaseOrderItemRepository) {
        this.planService = planService;
        this.companyRepository = companyRepository;
        this.productRepository = productRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderItemRepository = purchaseOrderItemRepository;
    }

    /** `mesesPedidos` é o texto do query param — um valor inválido cai na janela do plano, nunca em "sem janela" nem em zero. */
    public Janela janelaDoPlano(CompanyPlan plan, String mesesPedidos) {
        Integer limite = planService.limite(plan, PlanLimit.HISTORICO_RELATORIOS_MESES);
        Integer pedidoValido = null;
        if (mesesPedidos != null && !mesesPedidos.isBlank()) {
            try {
                double pedido = Double.parseDouble(mesesPedidos.trim());
                if (Double.isFinite(pedido) && pedido > 0) pedidoValido = (int) Math.floor(pedido);
            } catch (NumberFormatException ignorado) {
                // fica null: cai na janela do plano
            }
        }
        if (limite == null) return new Janela(pedidoValido, pedidoValido == null, null, false);
        int meses = pedidoValido != null ? Math.min(pedidoValido, limite) : limite;
        return new Janela(meses, false, limite, pedidoValido != null && pedidoValido > limite);
    }

    /** Data de corte para a janela, ou null quando não há janela. */
    static Instant cortePara(Janela janela, Instant agora) {
        if (janela.meses() == null) return null;
        return ZonedDateTime.ofInstant(agora, ZoneId.systemDefault()).minusMonths(janela.meses()).toInstant();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> supplierStats(String supplierCompanyId, String meses) {
        Company empresa = companyRepository.findById(supplierCompanyId).orElse(null);
        Janela janela = janelaDoPlano(empresa == null ? null : empresa.getPlan(), meses);
        Instant corte = cortePara(janela, Instant.now());

        // Só o que é DATADO entra na janela. O catálogo é estado presente e não se corta por data.
        List<Product> products = productRepository.findBySupplierId(supplierCompanyId);
        List<PurchaseOrder> orders = corte == null
                ? purchaseOrderRepository.findBySupplierCompanyId(supplierCompanyId)
                : purchaseOrderRepository.findBySupplierCompanyIdAndCreatedAtGreaterThanEqual(supplierCompanyId, corte);
        List<PurchaseOrderItem> items = orders.isEmpty() ? List.of()
                : purchaseOrderItemRepository.findByPurchaseOrderIdIn(orders.stream().map(PurchaseOrder::getId).toList());

        long activeProducts = products.stream().filter(Product::isActive).count();
        long lowStock = products.stream().filter(p -> p.isActive() && p.getStockQuantity() != null && p.getMinStock() != null
                && p.getStockQuantity() <= p.getMinStock()).count();

        Map<String, Long> statusCounts = new LinkedHashMap<>();
        for (PurchaseOrder o : orders) statusCounts.merge(o.getStatus().name(), 1L, Long::sum);
        BigDecimal revenue = orders.stream().filter(o -> PAID_STATUSES.contains(o.getStatus()))
                .map(PurchaseOrder::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        // Produtos mais vendidos (agregação em memória — volumes de MVP).
        Map<String, Map<String, Object>> byProduct = new LinkedHashMap<>();
        for (PurchaseOrderItem it : items) {
            Map<String, Object> cur = byProduct.computeIfAbsent(it.getProductId(), id -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("productId", id);
                m.put("name", it.getProduct() == null ? "—" : it.getProduct().getName());
                m.put("quantity", 0);
                m.put("total", BigDecimal.ZERO);
                return m;
            });
            cur.put("quantity", (Integer) cur.get("quantity") + it.getQuantity());
            cur.put("total", ((BigDecimal) cur.get("total")).add(it.getLineTotal()));
        }
        List<Map<String, Object>> topProducts = new ArrayList<>(byProduct.values());
        topProducts.sort(Comparator.comparingInt((Map<String, Object> m) -> (Integer) m.get("quantity")).reversed());
        if (topProducts.size() > 10) topProducts = topProducts.subList(0, 10);
        for (Map<String, Object> m : topProducts) m.put("total", Decimais.numero((BigDecimal) m.get("total"))); // cur.total += Number(it.lineTotal)

        List<Map<String, Object>> topViewed = products.stream().filter(p -> p.getViewCount() > 0)
                .sorted(Comparator.comparingInt(Product::getViewCount).reversed()).limit(10)
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("productId", p.getId());
                    m.put("name", p.getName());
                    m.put("views", p.getViewCount());
                    return m;
                }).toList();
        int totalViews = topViewed.stream().mapToInt(m -> (Integer) m.get("views")).sum();

        Map<String, Object> janelaOut = new LinkedHashMap<>();
        janelaOut.put("meses", janela.meses());
        janelaOut.put("ilimitado", janela.ilimitado());
        janelaOut.put("limitePlano", janela.limitePlano());
        janelaOut.put("truncada", janela.truncada());
        janelaOut.put("desde", corte == null ? null : corte.toString());
        // Estes números NÃO respeitam a janela: o catálogo é estado presente e viewCount é um contador sem datas.
        janelaOut.put("semJanela", List.of("totalProducts", "activeProducts", "lowStock", "totalViews", "topViewed"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("janela", janelaOut);
        out.put("totalProducts", products.size());
        out.put("activeProducts", activeProducts);
        out.put("lowStock", lowStock);
        out.put("totalOrders", orders.size());
        out.put("statusCounts", statusCounts);
        out.put("revenue", Decimais.numero(revenue)); // Number(revenueAgg._sum.totalAmount || 0)
        out.put("totalViews", totalViews);
        out.put("topProducts", topProducts);
        out.put("topViewed", topViewed);
        return out;
    }
}
