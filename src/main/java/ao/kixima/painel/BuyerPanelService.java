package ao.kixima.painel;

import ao.kixima.common.Decimais;

import ao.kixima.catalog.Product;
import ao.kixima.catalog.ProductRepository;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyRepository;
import ao.kixima.company.CompanyStatus;
import ao.kixima.company.CompanyType;
import ao.kixima.invoice.Invoice;
import ao.kixima.invoice.InvoiceStatus;
import ao.kixima.payment.Payment;
import ao.kixima.payment.PaymentRepository;
import ao.kixima.po.PoStatus;
import ao.kixima.po.PurchaseOrder;
import ao.kixima.po.PurchaseOrderItem;
import ao.kixima.po.PurchaseOrderRepository;
import ao.kixima.user.ProfileService;
import ao.kixima.user.User;
import ao.kixima.user.UserRepository;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static ao.kixima.painel.CompanyAdminPanelService.mapa;
import static ao.kixima.painel.CompanyAdminPanelService.soma;

/**
 * Espelha buyerService.js — agregações de leitura das telas do Comprador
 * (Ordens, Pagamentos, Acompanhar Entrega, Recepção, Fornecedores, Atividades, Perfil).
 */
@Service
public class BuyerPanelService {

    static final Set<PoStatus> ACTIVE = Set.of(PoStatus.AGUARDANDO_APROVACAO, PoStatus.APROVADA, PoStatus.ACEITE_FORNECEDOR,
            PoStatus.AGUARDANDO_PAGAMENTO, PoStatus.PAGA, PoStatus.EM_EXECUCAO, PoStatus.ENTREGUE);
    static final Set<PoStatus> RECEIVED = Set.of(PoStatus.RECEBIDA_CONFORME, PoStatus.RECEBIDA_COM_DIVERGENCIA, PoStatus.CONCLUIDA);
    static final Set<PoStatus> CANCELLED = Set.of(PoStatus.REJEITADA, PoStatus.RECUSADA_FORNECEDOR);
    private static final Set<PoStatus> ANDAMENTO = Set.of(PoStatus.APROVADA, PoStatus.ACEITE_FORNECEDOR, PoStatus.AGUARDANDO_PAGAMENTO,
            PoStatus.PAGA, PoStatus.EM_EXECUCAO, PoStatus.ENTREGUE);

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PaymentRepository paymentRepository;
    private final CompanyRepository companyRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    public BuyerPanelService(PurchaseOrderRepository purchaseOrderRepository, PaymentRepository paymentRepository,
                             CompanyRepository companyRepository, ProductRepository productRepository, UserRepository userRepository) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.paymentRepository = paymentRepository;
        this.companyRepository = companyRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
    }

    /** SUPPLIER_SEL. */
    static Map<String, Object> supplier(Company c) {
        if (c == null) return null;
        return mapa("id", c.getId(), "name", c.getName(), "logoUrl", c.getLogoUrl(), "verified", c.isVerified(),
                "city", c.getCity(), "province", c.getProvince(), "country", c.getCountry());
    }

    static int itemsCount(PurchaseOrder po) {
        return po.getItems().stream().mapToInt(PurchaseOrderItem::getQuantity).sum();
    }

    private static boolean contem(String texto, String q) {
        return texto != null && texto.toLowerCase().contains(q.toLowerCase());
    }

    // --- Ordens de Compra (item 6) --------------------------------------------

    private static Specification<PurchaseOrder> ordensSpec(String buyerCompanyId, String status, String q) {
        return (root, query, cb) -> {
            List<Predicate> conds = new ArrayList<>();
            conds.add(cb.equal(root.get("buyerCompanyId"), buyerCompanyId));
            if ("ANDAMENTO".equals(status)) conds.add(root.get("status").in(ANDAMENTO));
            else if ("CONCLUIDAS".equals(status)) conds.add(root.get("status").in(RECEIVED));
            else if ("CANCELADAS".equals(status)) conds.add(root.get("status").in(CANCELLED));
            else if (status != null && !status.isBlank()) conds.add(cb.equal(root.get("status"), PoStatus.valueOf(status)));
            if (q != null && !q.isBlank()) {
                Join<Object, Object> supplier = root.join("supplierCompany");
                String like = "%" + q.toLowerCase() + "%";
                conds.add(cb.or(cb.like(cb.lower(root.get("reference")), like), cb.like(cb.lower(supplier.get("name")), like)));
            }
            return cb.and(conds.toArray(new Predicate[0]));
        };
    }

    @Transactional(readOnly = true)
    public Map<String, Object> orders(String buyerCompanyId, String status, String q, int page, int limit) {
        List<PurchaseOrder> all = purchaseOrderRepository.findByBuyerCompanyIdOrderByCreatedAtDesc(buyerCompanyId);
        Specification<PurchaseOrder> spec = ordensSpec(buyerCompanyId, status, q);
        var rows = purchaseOrderRepository.findAll(spec, PageRequest.of(page - 1, limit, Sort.by(Sort.Direction.DESC, "createdAt")));
        long total = rows.getTotalElements();
        Map<String, Object> kpis = mapa("total", all.size(),
                "valorTotal", soma(all, PurchaseOrder::getTotalAmount),
                "emAndamento", all.stream().filter(p -> ACTIVE.contains(p.getStatus()) && p.getStatus() != PoStatus.AGUARDANDO_APROVACAO).count(),
                "concluidas", all.stream().filter(p -> RECEIVED.contains(p.getStatus())).count(),
                "canceladas", all.stream().filter(p -> CANCELLED.contains(p.getStatus())).count());
        return mapa("kpis", kpis, "items", rows.getContent().stream().map(BuyerPanelService::shapeOrder).toList(),
                "total", total, "page", page, "pages", Math.max(1, (int) Math.ceil(total / (double) limit)));
    }

    static Map<String, Object> shapeOrder(PurchaseOrder po) {
        return mapa("id", po.getId(), "reference", po.getReference(), "status", po.getStatus().name(),
                "supplier", supplier(po.getSupplierCompany()), "itemsCount", itemsCount(po),
                "totalAmount", Decimais.numero(po.getTotalAmount()), "currency", po.getCurrency(),
                "isCallOff", po.isCallOff(), "createdAt", po.getCreatedAt(), "acceptedAt", po.getAcceptedAt(),
                "paymentDueAt", po.getPaymentDueAt(), "dispatchedAt", po.getDispatchedAt(),
                "deliveredAt", po.getDeliveredAt(), "receivedAt", po.getReceivedAt(), "receptionStatus", po.getReceptionStatus());
    }

    // --- Pagamentos (item 7) --------------------------------------------------

    @Transactional(readOnly = true)
    public Map<String, Object> payments(String buyerCompanyId, String status, String q) {
        List<PurchaseOrder> orders = purchaseOrderRepository.findByBuyerCompanyIdOrderByCreatedAtDesc(buyerCompanyId).stream()
                .filter(po -> po.getInvoice() != null).toList();
        Map<String, Payment> pagamentos = paymentRepository.findByInvoiceIdIn(orders.stream().map(po -> po.getInvoice().getId()).toList())
                .stream().collect(Collectors.toMap(Payment::getInvoiceId, p -> p, (a, b) -> a));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (PurchaseOrder po : orders) {
            Invoice inv = po.getInvoice();
            boolean paid = inv.getStatus() == InvoiceStatus.PAGA;
            Payment pay = pagamentos.get(inv.getId());
            rows.add(mapa("id", inv.getId(), "poId", po.getId(), "reference", po.getReference(), "invoiceRef", inv.getReference(),
                    "supplier", supplier(po.getSupplierCompany()), "poDate", po.getCreatedAt(), "dueAt", inv.getDueAt(),
                    "amount", Decimais.numero(inv.getAmount()), "paid", Decimais.numero(paid ? inv.getAmount() : (pay != null ? pay.getAmount() : BigDecimal.ZERO)),
                    "open", Decimais.numero(paid ? BigDecimal.ZERO : inv.getAmount()), "status", inv.getStatus().name(), "currency", inv.getCurrency(),
                    "origin", po.isCallOff() ? "Call-off" : "Gerada a partir do Checkout"));
        }
        if ("ABERTO".equals(status)) rows = rows.stream().filter(r -> "PENDENTE".equals(r.get("status"))).toList();
        else if ("ATRASADO".equals(status)) rows = rows.stream().filter(r -> "VENCIDA".equals(r.get("status"))).toList();
        else if ("CONCLUIDO".equals(status)) rows = rows.stream().filter(r -> "PAGA".equals(r.get("status"))).toList();
        if (q != null && !q.isBlank()) {
            rows = rows.stream().filter(r -> contem((String) r.get("reference"), q) || contem(nomeDe(r.get("supplier")), q)).toList();
        }

        Instant monthStart = Meses.inicioDoMes();
        List<Invoice> all = orders.stream().map(PurchaseOrder::getInvoice).toList();
        Map<String, Object> kpis = mapa(
                "aPagar", Decimais.numero(somaFaturas(all.stream().filter(i -> i.getStatus() == InvoiceStatus.PENDENTE).toList())),
                "concluidos", Decimais.numero(somaFaturas(all.stream().filter(i -> i.getStatus() == InvoiceStatus.PAGA && i.getUpdatedAt() != null && !i.getUpdatedAt().isBefore(monthStart)).toList())),
                "atrasados", Decimais.numero(somaFaturas(all.stream().filter(i -> i.getStatus() == InvoiceStatus.VENCIDA).toList())),
                "totalPO", soma(orders, PurchaseOrder::getTotalAmount));
        return mapa("kpis", kpis, "items", rows);
    }

    @SuppressWarnings("unchecked")
    private static String nomeDe(Object supplier) {
        return supplier == null ? null : (String) ((Map<String, Object>) supplier).get("name");
    }

    static BigDecimal somaFaturas(List<Invoice> is) {
        return is.stream().map(Invoice::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // --- Acompanhar Entrega (item 8) -------------------------------------------

    private static Map<String, Object> deliveryStage(PurchaseOrder po) {
        if (po.getReceivedAt() != null || po.getDeliveredAt() != null) return mapa("stage", "ENTREGUE", "label", "Entregue", "progress", 100);
        if (po.getDispatchedAt() != null) return mapa("stage", "EM_TRANSITO", "label", "Em Trânsito", "progress", 75);
        return mapa("stage", "EM_PREPARACAO", "label", "Em Preparação", "progress", 30);
    }

    private static Map<String, Object> estagio(PurchaseOrder po) {
        return CANCELLED.contains(po.getStatus()) ? mapa("stage", "CANCELADA", "label", "Cancelada", "progress", 0) : deliveryStage(po);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> deliveries(String buyerCompanyId, String stage, String q) {
        Set<PoStatus> estados = new HashSet<>(Set.of(PoStatus.PAGA, PoStatus.EM_EXECUCAO, PoStatus.ENTREGUE));
        estados.addAll(RECEIVED);
        estados.addAll(CANCELLED);
        List<PurchaseOrder> orders = purchaseOrderRepository.findByBuyerCompanyIdAndStatusInOrderByCreatedAtDesc(buyerCompanyId, estados);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PurchaseOrder po : orders) {
            Company s = po.getSupplierCompany();
            Map<String, Object> r = mapa("id", po.getId(), "reference", po.getReference(), "supplier", supplier(s), "itemsCount", itemsCount(po),
                    "emittedAt", po.getCreatedAt(), "dispatchedAt", po.getDispatchedAt(), "deliveredAt", po.getDeliveredAt());
            r.putAll(estagio(po));
            r.put("location", s != null && s.getCity() != null && !s.getCity().isBlank()
                    ? s.getCity() + ", " + (s.getCountry() == null ? "Angola" : s.getCountry()) : "—");
            rows.add(r);
        }
        if (stage != null && !stage.isBlank() && !"TODAS".equals(stage)) rows = rows.stream().filter(r -> stage.equals(r.get("stage"))).toList();
        if (q != null && !q.isBlank()) {
            rows = rows.stream().filter(r -> contem((String) r.get("reference"), q) || contem(nomeDe(r.get("supplier")), q)).toList();
        }
        List<String> src = orders.stream().map(po -> (String) estagio(po).get("stage")).toList();
        return mapa("kpis", mapa("emTransito", src.stream().filter("EM_TRANSITO"::equals).count(),
                        "emPreparacao", src.stream().filter("EM_PREPARACAO"::equals).count(),
                        "entregues", src.stream().filter("ENTREGUE"::equals).count(),
                        "canceladas", src.stream().filter("CANCELADA"::equals).count()),
                "items", rows);
    }

    // --- Recepção (item 9) ----------------------------------------------------

    @Transactional(readOnly = true)
    public Map<String, Object> receptions(String buyerCompanyId, String status, String q) {
        Set<PoStatus> estados = new HashSet<>(RECEIVED);
        estados.add(PoStatus.ENTREGUE);
        List<PurchaseOrder> orders = purchaseOrderRepository.findByBuyerCompanyIdAndStatusInOrderByUpdatedAtDesc(buyerCompanyId, estados);
        List<Map<String, Object>> rows = new ArrayList<>();
        int aReceber = 0, recebidos = 0, divergencia = 0, total = 0;
        for (PurchaseOrder po : orders) {
            String st = po.getStatus() == PoStatus.RECEBIDA_CONFORME || po.getStatus() == PoStatus.CONCLUIDA ? "RECEBIDO"
                    : po.getStatus() == PoStatus.RECEBIDA_COM_DIVERGENCIA ? "DIVERGENCIA" : "A_RECEBER";
            for (PurchaseOrderItem it : po.getItems()) {
                Product p = it.getProduct();
                rows.add(mapa("id", po.getId() + ":" + it.getId(), "poId", po.getId(), "reference", po.getReference(),
                        "supplier", supplier(po.getSupplierCompany()), "item", p == null ? null : p.getName(), "sku", p == null ? null : p.getSku(),
                        "quantity", it.getQuantity(), "receivedAt", po.getReceivedAt(), "status", st, "receptionStatus", po.getReceptionStatus()));
                total++;
                if (po.getStatus() == PoStatus.ENTREGUE) aReceber++;
                else if (po.getStatus() == PoStatus.RECEBIDA_COM_DIVERGENCIA) divergencia++;
                else recebidos++;
            }
        }
        if (status != null && !status.isBlank() && !"TODOS".equals(status)) rows = rows.stream().filter(r -> status.equals(r.get("status"))).toList();
        if (q != null && !q.isBlank()) {
            rows = rows.stream().filter(r -> contem((String) r.get("reference"), q) || contem(nomeDe(r.get("supplier")), q)
                    || contem((String) r.get("item"), q)).toList();
        }
        return mapa("kpis", mapa("aReceber", aReceber, "recebidos", recebidos, "divergencia", divergencia, "total", total), "items", rows);
    }

    // --- Fornecedores (item 10) ----------------------------------------------

    @Transactional(readOnly = true)
    public Map<String, Object> suppliers(String buyerCompanyId, String status, String q) {
        List<CompanyStatus> estados = List.of(CompanyStatus.APROVADA, CompanyStatus.PENDENTE);
        List<Company> companies = q == null || q.isBlank()
                ? companyRepository.findByTypeAndStatusInOrderByCreatedAtDesc(CompanyType.FORNECEDOR, estados)
                : companyRepository.findByTypeAndStatusInAndNameContainingIgnoreCaseOrderByCreatedAtDesc(CompanyType.FORNECEDOR, estados, q);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Company c : companies) {
            List<Product> ativos = productRepository.findBySupplierId(c.getId()).stream().filter(Product::isActive).toList();
            double media = ativos.stream().filter(p -> p.getRating() != null).mapToDouble(Product::getRating).average().orElse(Double.NaN);
            String topCategory = ativos.stream().collect(Collectors.groupingBy(p -> p.getCategory() == null ? "" : p.getCategory(), Collectors.counting()))
                    .entrySet().stream().max(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey(java.util.Comparator.reverseOrder())))
                    .map(Map.Entry::getKey).filter(k -> !k.isBlank()).orElse("—");
            List<PurchaseOrder> lastPo = purchaseOrderRepository.findFirstBySupplierCompanyIdAndBuyerCompanyIdOrderByCreatedAtDesc(c.getId(), buyerCompanyId, PageRequest.of(0, 1));
            rows.add(mapa("id", c.getId(), "name", c.getName(), "logoUrl", c.getLogoUrl(), "verified", c.isVerified(), "status", c.getStatus().name(),
                    "city", c.getCity(), "country", c.getCountry(), "category", topCategory,
                    "rating", Double.isNaN(media) ? null : Math.round(media * 10) / 10.0,
                    "productCount", ativos.size(),
                    "lastTransaction", lastPo.isEmpty() ? null : mapa("reference", lastPo.get(0).getReference(), "createdAt", lastPo.get(0).getCreatedAt())));
        }
        List<Map<String, Object>> list = rows;
        if ("HOMOLOGADOS".equals(status)) list = rows.stream().filter(r -> Boolean.TRUE.equals(r.get("verified"))).toList();
        else if ("AVALIACAO".equals(status)) list = rows.stream().filter(r -> !Boolean.TRUE.equals(r.get("verified"))).toList();
        else if ("ATIVOS".equals(status)) list = rows.stream().filter(r -> "APROVADA".equals(r.get("status"))).toList();
        Instant d30 = Instant.now().minus(30, ChronoUnit.DAYS);
        return mapa("kpis", mapa("total", rows.size(),
                        "ativos", rows.stream().filter(r -> "APROVADA".equals(r.get("status"))).count(),
                        "homologados", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("verified"))).count(),
                        "emAvaliacao", rows.stream().filter(r -> !Boolean.TRUE.equals(r.get("verified"))).count(),
                        "novos", companies.stream().filter(c -> c.getCreatedAt() != null && !c.getCreatedAt().isBefore(d30)).count()),
                "items", list);
    }

    // --- Atividades (item 11) --------------------------------------------------

    @Transactional(readOnly = true)
    public Map<String, Object> activities(String buyerCompanyId, String filter) {
        List<PurchaseOrder> orders = purchaseOrderRepository.findByBuyerCompanyIdOrderByUpdatedAtDesc(buyerCompanyId);
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (PurchaseOrder po : orders) {
            Company s = po.getSupplierCompany();
            Map<String, Object> base = mapa("relatedTo", po.getReference(), "poId", po.getId(), "supplier", s == null ? null : s.getName(), "at", po.getUpdatedAt());
            Map<String, Object> t = null;
            if (po.getStatus() == PoStatus.AGUARDANDO_APROVACAO) t = tarefa("APROVAR", "Aprovar Ordem de Compra", "Rever e aprovar a PO antes do envio ao fornecedor.", "PENDENTE", "ALTA");
            else if (po.getStatus() == PoStatus.AGUARDANDO_PAGAMENTO || (po.getInvoice() != null && po.getInvoice().getStatus() == InvoiceStatus.PENDENTE))
                t = tarefa("PAGAR", "Autorizar Pagamento", "Rever e autorizar o pagamento da fatura.", "PENDENTE", "ALTA");
            else if (po.getStatus() == PoStatus.ENTREGUE) t = tarefa("RECEBER", "Confirmar Recepção", "Confirmar a recepção dos itens entregues.", "AGUARDANDO", "MEDIA");
            else if (po.getStatus() == PoStatus.EM_EXECUCAO && po.getDispatchedAt() != null) t = tarefa("ENTREGA", "Acompanhar Entrega", "Verificar o estado da entrega em andamento.", "EM_ANDAMENTO", "MEDIA");
            else if (po.getStatus() == PoStatus.RECEBIDA_COM_DIVERGENCIA) t = tarefa("DIVERGENCIA", "Resolver Divergência", "Analisar divergência na recepção de itens.", "ATRASADA", "ALTA");
            else if (RECEIVED.contains(po.getStatus())) t = tarefa("CONCLUIDA", "Ordem concluída", "Recepção confirmada com sucesso.", "CONCLUIDA", "BAIXA");
            if (t != null) {
                base.putAll(t);
                tasks.add(base);
            }
        }
        List<Map<String, Object>> list = tasks;
        if ("PENDENTES".equals(filter)) list = filtrar(tasks, "PENDENTE");
        else if ("ANDAMENTO".equals(filter)) list = filtrar(tasks, "EM_ANDAMENTO");
        else if ("CONCLUIDAS".equals(filter)) list = filtrar(tasks, "CONCLUIDA");
        else if ("ATRASADAS".equals(filter)) list = filtrar(tasks, "ATRASADA");
        return mapa("kpis", mapa("minhasTarefas", filtrar(tasks, "PENDENTE").size(), "aguardando", filtrar(tasks, "AGUARDANDO").size(),
                        "concluidas", filtrar(tasks, "CONCLUIDA").size(), "atrasadas", filtrar(tasks, "ATRASADA").size(),
                        "emAndamento", filtrar(tasks, "EM_ANDAMENTO").size()),
                "items", list);
    }

    private static Map<String, Object> tarefa(String type, String title, String desc, String status, String priority) {
        return mapa("type", type, "title", title, "desc", desc, "status", status, "priority", priority);
    }

    private static List<Map<String, Object>> filtrar(List<Map<String, Object>> tasks, String status) {
        return tasks.stream().filter(t -> status.equals(t.get("status"))).toList();
    }

    // --- Perfil (item 12) -------------------------------------------------------

    @Transactional(readOnly = true)
    public Map<String, Object> profile(String userId, String companyId) {
        User u = userRepository.findById(userId).orElse(null);
        Company company = companyId == null ? null : companyRepository.findById(companyId).orElse(null);
        List<PurchaseOrder> orders = companyId == null ? List.of() : purchaseOrderRepository.findByBuyerCompanyIdOrderByUpdatedAtDesc(companyId);
        Instant yearStart = Meses.inicioDoAno();
        List<PurchaseOrder> yearOrders = orders.stream().filter(o -> !o.getCreatedAt().isBefore(yearStart)).toList();
        Collection<String> suppliers = orders.stream().map(PurchaseOrder::getSupplierCompanyId).collect(Collectors.toSet());
        int itemsReceived = orders.stream().filter(o -> RECEIVED.contains(o.getStatus())).mapToInt(BuyerPanelService::itemsCount).sum();
        List<Map<String, Object>> recent = orders.stream().limit(5).map(o -> mapa("reference", o.getReference(),
                "supplier", o.getSupplierCompany() == null ? null : o.getSupplierCompany().getName(), "status", o.getStatus().name(), "at", o.getUpdatedAt())).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", u == null ? null : new ProfileService.UserRef(u.getId(), u.getName(), u.getEmail(), u.getRole().name(), u.getAvatarUrl(), u.getCreatedAt()));
        out.put("company", company == null ? null : ProfileService.CompanyRef.de(company));
        out.put("summary", mapa("ordersYear", yearOrders.size(), "totalBought", soma(yearOrders, PurchaseOrder::getTotalAmount),
                "suppliers", suppliers.size(), "itemsReceived", itemsReceived));
        out.put("recent", recent);
        return out;
    }
}
