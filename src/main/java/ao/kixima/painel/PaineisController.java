package ao.kixima.painel;

import ao.kixima.common.Decimais;

import ao.kixima.company.CompanyRepository;
import ao.kixima.company.CompanyStatus;
import ao.kixima.company.CompanyType;
import ao.kixima.po.PoStatus;
import ao.kixima.po.PurchaseOrder;
import ao.kixima.po.PurchaseOrderRepository;
import ao.kixima.security.CurrentUser;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.RequireRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ao.kixima.painel.CompanyAdminPanelService.mapa;
import static ao.kixima.security.PersonaRole.COMPANY_ADMIN;
import static ao.kixima.security.PersonaRole.COMPRADOR;
import static ao.kixima.security.PersonaRole.FINANCEIRO;

/**
 * Espelha dashboardRoutes.js e o `/stats` de publicRoutes.js — os painéis do
 * Company Admin, Comprador e Financeiro estão nos controladores ao lado.
 */
@RestController
public class PaineisController {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final CompanyRepository companyRepository;
    private final int paymentSlaDays;

    public PaineisController(PurchaseOrderRepository purchaseOrderRepository, CompanyRepository companyRepository,
                             @Value("${kixima.business.payment-sla-days:7}") int paymentSlaDays) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.companyRepository = companyRepository;
        this.paymentSlaDays = paymentSlaDays;
    }

    private static String companyId() {
        return CurrentUserHolder.get().companyId();
    }




    // --- Dashboard do Comprador (dashboardService.buyerSummary) -------------------

    private static final Set<PoStatus> ACTIVE = BuyerPanelService.ACTIVE;
    private static final Set<PoStatus> RECEIVED = BuyerPanelService.RECEIVED;
    private static final Set<PoStatus> CANCELLED = BuyerPanelService.CANCELLED;

    @GetMapping("/api/dashboard/comprador")
    @RequireRole({COMPRADOR})
    @Transactional(readOnly = true)
    public Map<String, Object> dashboardComprador() {
        List<PurchaseOrder> pos = purchaseOrderRepository.findByBuyerCompanyIdOrderByCreatedAtDesc(companyId());
        Map<PoStatus, Long> byStatus = new java.util.EnumMap<>(PoStatus.class);
        long emAndamento = 0, aguardCount = 0, entregaCount = 0, recebidasMes = 0;
        BigDecimal aguardTotal = BigDecimal.ZERO, entregaTotal = BigDecimal.ZERO;
        var mStart = Meses.inicioDoMes();
        for (PurchaseOrder p : pos) {
            byStatus.merge(p.getStatus(), 1L, Long::sum);
            if (ACTIVE.contains(p.getStatus())) emAndamento++;
            if (p.getStatus() == PoStatus.AGUARDANDO_PAGAMENTO) { aguardCount++; aguardTotal = aguardTotal.add(p.getTotalAmount()); }
            if (p.getStatus() == PoStatus.EM_EXECUCAO || p.getStatus() == PoStatus.ENTREGUE) { entregaCount++; entregaTotal = entregaTotal.add(p.getTotalAmount()); }
            if (RECEIVED.contains(p.getStatus()) && !p.getCreatedAt().isBefore(mStart)) recebidasMes++;
        }
        java.util.function.Function<Set<PoStatus>, Long> sum = sts -> sts.stream().mapToLong(s -> byStatus.getOrDefault(s, 0L)).sum();
        List<Map<String, Object>> minhasOrdens = new ArrayList<>(List.of(
                mapa("label", "Aguardando Pagamento", "count", byStatus.getOrDefault(PoStatus.AGUARDANDO_PAGAMENTO, 0L)),
                mapa("label", "Em Execução", "count", byStatus.getOrDefault(PoStatus.EM_EXECUCAO, 0L)),
                mapa("label", "Em Entrega", "count", byStatus.getOrDefault(PoStatus.ENTREGUE, 0L)),
                mapa("label", "Recebidas", "count", sum.apply(RECEIVED)),
                mapa("label", "Canceladas", "count", sum.apply(CANCELLED))));
        return mapa("kpis", mapa("emAndamento", emAndamento,
                        "aguardandoPagamento", mapa("count", aguardCount, "total", Decimais.numero(aguardTotal)),
                        "emEntrega", mapa("count", entregaCount, "total", Decimais.numero(entregaTotal)),
                        "recebidasMes", recebidasMes),
                "minhasOrdens", minhasOrdens);
    }

    // --- Estatísticas públicas (publicStatsService.resumo) -------------------------

    /** Só contagens, nunca dados de uma empresa em concreto — sem autenticação (PublicPaths). */
    @GetMapping("/api/public/stats")
    @Transactional(readOnly = true)
    public Map<String, Object> publicStats() {
        return mapa("empresasVerificadas", companyRepository.countByStatus(CompanyStatus.APROVADA),
                "fornecedoresQualificados", companyRepository.countByStatusAndType(CompanyStatus.APROVADA, CompanyType.FORNECEDOR),
                "ordensProcessadas", purchaseOrderRepository.countByStatus(PoStatus.CONCLUIDA),
                "pagamentoSlaDias", paymentSlaDays);
    }
}
