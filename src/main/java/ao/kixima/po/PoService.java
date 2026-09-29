package ao.kixima.po;

import ao.kixima.agt.AgtSandboxSubmissionService;
import ao.kixima.audit.Actor;
import ao.kixima.audit.AuditLog;
import ao.kixima.audit.AuditLogRepository;
import ao.kixima.audit.AuditService;
import ao.kixima.catalog.Product;
import ao.kixima.catalog.ProductRepository;
import ao.kixima.common.error.BusinessRuleException;
import ao.kixima.common.error.ConflictException;
import ao.kixima.common.error.ForbiddenException;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.common.reference.ReferenceCounterService;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyRepository;
import ao.kixima.erp.CompanyErpConfigRepository;
import ao.kixima.erp.ErpFields;
import ao.kixima.erp.ErpSyncDirection;
import ao.kixima.erp.ErpSyncLog;
import ao.kixima.erp.ErpSyncLogRepository;
import ao.kixima.erp.ErpSyncStatus;
import ao.kixima.contract.Contract;
import ao.kixima.contract.ContractService;
import ao.kixima.faturacao.FaturacaoService;
import ao.kixima.conciliacao.ConciliacaoService;
import ao.kixima.messaging.EventBus;
import ao.kixima.messaging.EventPayloads;
import ao.kixima.invoice.Invoice;
import ao.kixima.invoice.InvoiceLine;
import ao.kixima.invoice.InvoiceLineRepository;
import ao.kixima.invoice.InvoiceRepository;
import ao.kixima.invoice.InvoiceStatus;
import ao.kixima.notification.NotificationService;
import ao.kixima.payment.Payment;
import ao.kixima.payment.PaymentRepository;
import ao.kixima.payment.PlatformFeeService;
import ao.kixima.plan.PlanFeatureFlag;
import ao.kixima.plan.PlanService;
import ao.kixima.security.CurrentUser;
import ao.kixima.security.PersonaRole;
import ao.kixima.tax.TaxService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Espelha backend/src/services/poService.js — o fluxo principal end-to-end
 * (checkout → aprovação → aceitação/fatura → despacho → entrega → receção →
 * fecho). Ver o cabeçalho do ficheiro Node para a numeração dos 8 passos,
 * reproduzida nos comentários dos métodos abaixo.
 *
 * Também já cá estão o call-off / contrato-quadro (A.5: createPurchaseOrder
 * consulta {@link ContractService#findActiveContractForOrder} e a PO nasce
 * {@code isCallOff=true}, sem aprovação) e a ERP DOA Approval (A.7:
 * {@link #aplicarDecisaoErp}/{@link #aplicarPagamentoErp}). O estado da PO,
 * a matemática de imposto, a cadeia de hash da fatura, a referência de
 * pagamento e a decrementação atómica de stock — o núcleo que move dinheiro
 * — estão completos e testados.
 */
@Service
public class PoService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final ProductRepository productRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceLineRepository invoiceLineRepository;
    private final TaxService taxService;
    private final FaturacaoService faturacaoService;
    private final ConciliacaoService conciliacaoService;
    private final ReferenceCounterService referenceCounterService;
    private final AgtSandboxSubmissionService agtSandboxSubmissionService;
    private final NotificationService notificationService;
    private final EventBus eventBus;
    private final ContractService contractService;
    private final CompanyRepository companyRepository;
    private final PlanService planService;
    private final CompanyErpConfigRepository companyErpConfigRepository;
    private final ErpSyncLogRepository erpSyncLogRepository;
    private final AuditService auditService;
    private final AuditLogRepository auditLogRepository;
    private final PaymentRepository paymentRepository;
    private final PlatformFeeService platformFeeService;
    private final int paymentSlaDays;

    @PersistenceContext
    private EntityManager entityManager;

    /** `ATOR_ERP` de poService.js — as decisões vindas do callback assinado são do ERP, não de um utilizador. */
    static final Actor ATOR_ERP = new Actor(null, "ERP", null, null, null);

    public PoService(PurchaseOrderRepository purchaseOrderRepository, PurchaseOrderItemRepository purchaseOrderItemRepository,
                      ProductRepository productRepository, InvoiceRepository invoiceRepository,
                      InvoiceLineRepository invoiceLineRepository, TaxService taxService, FaturacaoService faturacaoService,
                      ConciliacaoService conciliacaoService, ReferenceCounterService referenceCounterService,
                      AgtSandboxSubmissionService agtSandboxSubmissionService, NotificationService notificationService,
                      EventBus eventBus, ContractService contractService, CompanyRepository companyRepository,
                      PlanService planService, CompanyErpConfigRepository companyErpConfigRepository,
                      ErpSyncLogRepository erpSyncLogRepository, AuditService auditService, AuditLogRepository auditLogRepository,
                      PaymentRepository paymentRepository, PlatformFeeService platformFeeService,
                      @Value("${kixima.business.payment-sla-days:7}") int paymentSlaDays) {
        this.eventBus = eventBus;
        this.companyRepository = companyRepository;
        this.planService = planService;
        this.companyErpConfigRepository = companyErpConfigRepository;
        this.erpSyncLogRepository = erpSyncLogRepository;
        this.auditService = auditService;
        this.auditLogRepository = auditLogRepository;
        this.paymentRepository = paymentRepository;
        this.platformFeeService = platformFeeService;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderItemRepository = purchaseOrderItemRepository;
        this.productRepository = productRepository;
        this.invoiceRepository = invoiceRepository;
        this.invoiceLineRepository = invoiceLineRepository;
        this.taxService = taxService;
        this.faturacaoService = faturacaoService;
        this.conciliacaoService = conciliacaoService;
        this.referenceCounterService = referenceCounterService;
        this.agtSandboxSubmissionService = agtSandboxSubmissionService;
        this.notificationService = notificationService;
        this.contractService = contractService;
        this.paymentSlaDays = paymentSlaDays;
    }

    public record ItemPedido(String productId, int quantity) {
    }

    // --- 1. Checkout: criação da PO -----------------------------------------

    @Transactional
    public PurchaseOrder createPurchaseOrder(String buyerCompanyId, String supplierCompanyId, String createdById,
                                              List<ItemPedido> items) {
        return createPurchaseOrder(buyerCompanyId, supplierCompanyId, createdById, items, "HUMANO");
    }

    /**
     * {@code createdBySource} só marca a origem ("HUMANO"/"ROBOT") para a
     * auditoria/interface distinguirem — o PO Robot chama isto TAL E QUAL,
     * sem via paralela: a PO nasce em AGUARDANDO_APROVACAO como qualquer outra.
     */
    @Transactional
    public PurchaseOrder createPurchaseOrder(String buyerCompanyId, String supplierCompanyId, String createdById,
                                              List<ItemPedido> items, String createdBySource) {
        if (items == null || items.isEmpty()) {
            throw new BusinessRuleException("A ordem de compra precisa de pelo menos um item.");
        }
        if (supplierCompanyId.equals(buyerCompanyId)) {
            throw new BusinessRuleException("Não pode comprar produtos da sua própria empresa.");
        }

        List<String> productIds = items.stream().map(ItemPedido::productId).toList();
        List<Product> products = productRepository.findAllById(productIds);
        if (products.size() != productIds.size()) {
            throw new NotFoundException("Um ou mais produtos");
        }
        boolean mismatched = products.stream().anyMatch(p -> !p.getSupplierId().equals(supplierCompanyId));
        if (mismatched) {
            throw new BusinessRuleException("Todos os itens da PO devem pertencer ao mesmo fornecedor.");
        }

        record LineItem(String productId, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
        }
        List<LineItem> lineItems = items.stream().map(i -> {
            Product product = products.stream().filter(p -> p.getId().equals(i.productId())).findFirst().orElseThrow();
            BigDecimal unitPrice = product.getUnitPrice();
            return new LineItem(product.getId(), i.quantity(), unitPrice, unitPrice.multiply(BigDecimal.valueOf(i.quantity())));
        }).toList();

        // IVA no servidor — o total da PO é o que o comprador se compromete a pagar (com imposto).
        var impostos = taxService.summarize(lineItems.stream()
                .map(li -> new TaxService.Line(li.lineTotal(),
                        products.stream().filter(p -> p.getId().equals(li.productId())).findFirst().orElseThrow().getKind()))
                .toList());

        // Stock: verificado E decrementado atomicamente por item, na mesma transação da criação da PO.
        // Aviso de stock baixo só na TRANSIÇÃO (mesmo critério de CatalogService.createStockMovement/
        // updateStock) — recolhido aqui, disparado só depois de a PO existir.
        record AvisoEstoqueBaixo(Product produto, int restante) {
        }
        List<AvisoEstoqueBaixo> avisosEstoqueBaixo = new java.util.ArrayList<>();
        for (LineItem li : lineItems) {
            Product produto = products.stream().filter(p -> p.getId().equals(li.productId())).findFirst().orElseThrow();
            if (produto.getStockQuantity() == null) continue; // null = não rastreado, sem limite.
            int reservado = productRepository.decrementStockIfAvailable(produto.getId(), li.quantity());
            if (reservado == 0) {
                Integer atual = productRepository.findById(produto.getId()).map(Product::getStockQuantity).orElse(0);
                throw new BusinessRuleException(
                        "Stock insuficiente para \"" + produto.getName() + "\": pediu " + li.quantity() + ", há " + atual + " em stock.");
            }
            Integer minStock = produto.getMinStock();
            int restante = produto.getStockQuantity() - li.quantity();
            if (minStock != null && produto.getStockQuantity() > minStock && restante <= minStock) {
                avisosEstoqueBaixo.add(new AvisoEstoqueBaixo(produto, restante));
            }
        }

        // Deteção automática de Call-off (secção 5): se o fornecedor tem contrato-quadro
        // ATIVO cobrindo todas as categorias, a PO vira Call-off sem o comprador fazer nada.
        List<String> categories = products.stream().map(Product::getCategory).distinct().toList();
        Contract contract = contractService.findActiveContractForOrder(buyerCompanyId, supplierCompanyId, categories).orElse(null);

        // ERP DOA Approval (PRO): quando o comprador tem a feature erpIntegration no
        // plano E um ERP real configurado (≠ MANUAL), a aprovação acontece no ERP dele,
        // não no KIXIMA. Call-offs ficam de fora — já nascem aprovados pela assinatura
        // do contrato-quadro, não há decisão nenhuma para o ERP tomar.
        boolean erpManaged = false;
        if (contract == null) {
            Company buyerCompany = companyRepository.findById(buyerCompanyId).orElse(null);
            if (buyerCompany != null && planService.hasFeature(buyerCompany.getPlan(), PlanFeatureFlag.ERP_INTEGRATION)) {
                erpManaged = companyErpConfigRepository.findByCompanyId(buyerCompanyId)
                        .map(cfg -> ErpFields.isRealErp(cfg.getErp())).orElse(false);
            }
        }

        String reference = referenceCounterService.nextReference("PO", "purchaseOrder");
        Instant agora = Instant.now();

        PurchaseOrder po = new PurchaseOrder(UUID.randomUUID().toString(), reference, buyerCompanyId, supplierCompanyId,
                createdById, PoStatus.AGUARDANDO_APROVACAO, impostos.gross(), impostos.net(), impostos.tax(),
                impostos.withheld(), false, erpManaged, erpManaged ? agora : null, createdBySource, null, agora, agora);
        // Call-off: a aprovação de negócio já aconteceu na assinatura do contrato.
        if (contract != null) po.nascerComoCallOff(contract.getId(), agora);
        purchaseOrderRepository.save(po);

        for (LineItem li : lineItems) {
            PurchaseOrderItem item = new PurchaseOrderItem(
                    UUID.randomUUID().toString(), po.getId(), li.productId(), li.quantity(), li.unitPrice(), li.lineTotal());
            purchaseOrderItemRepository.save(item);
            // `po` é uma entidade nova (não veio de uma leitura) — `items` (mappedBy, lazy)
            // nunca reflectiria os filhos só de os gravar; adiciona-se explicitamente para
            // a resposta (toDto) devolver os itens tal como o Node devolve na criação.
            po.getItems().add(item);
        }

        for (var aviso : avisosEstoqueBaixo) {
            notificationService.estoqueBaixo(aviso.produto().getSupplierId(), aviso.produto().getId(),
                    aviso.produto().getName(), aviso.restante(), aviso.produto().getMinStock());
        }

        if (contract != null) {
            // O tecto do contrato-quadro é um valor COMERCIAL: consome-se com o líquido, não com o IVA.
            contractService.consumir(contract.getId(), impostos.net());
            // Já aprovada -> segue diretamente para o fornecedor.
            notificationService.poRecebidaPeloFornecedor(po);
        } else if (erpManaged) {
            // O ERP corre o próprio workflow/DOA; a decisão chega de volta pelo callback
            // assinado (IntegrationCallbackController -> aplicarDecisaoErp). Não se
            // notifica o Company Admin para aprovar — não é ele quem decide agora.
            // No Node o `publish` é síncrono e devolve se publicou; aqui a publicação
            // corre depois do commit (EventBus), por isso o registo OUTBOUND reflete se
            // o barramento está sequer configurado — uma falha de envio fica no log do EventBus.
            entityManager.flush();
            entityManager.refresh(po); // carrega buyerCompany/supplierCompany/items.product para o payload
            boolean publicavel = eventBus.ativo();
            eventBus.publish("purchase_order.approval_requested", EventPayloads.purchaseOrderApprovalRequested(po, agora),
                    "po-approval-requested:" + po.getId(), buyerCompanyId);
            erpSyncLogRepository.save(new ErpSyncLog(po.getId(), ErpSyncDirection.OUTBOUND, "approval_requested",
                    publicavel ? ErpSyncStatus.SUCCESS : ErpSyncStatus.FAILED, null,
                    publicavel ? null : "Não foi possível publicar no barramento de eventos (broker indisponível)."));
        } else {
            notificationService.poAguardaAprovacao(po);
        }
        return po;
    }

    /** Linha do tempo auditável da PO — mesmo controlo de acesso de {@link #getPurchaseOrder}. */
    @Transactional(readOnly = true)
    public List<AuditLog> getPurchaseOrderHistory(String id, CurrentUser user) {
        getPurchaseOrder(id, user);
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtAsc("PurchaseOrder", id);
    }

    // --- ERP DOA Approval: decisão e pagamento vindos do callback assinado -----

    /**
     * Aplica a decisão (aprovação/rejeição) do ERP a uma PO erpManaged.
     * Idempotente por estado: só a primeira a reivindicar AGUARDANDO_APROVACAO
     * é aplicada; repetições devolvem a PO como está, sem duplicar
     * ErpSyncLog/auditoria/notificações.
     */
    @Transactional
    public PurchaseOrder aplicarDecisaoErp(String poId, boolean aprovado, String erpExternalId, String motivo) {
        PurchaseOrder po = purchaseOrderRepository.findByIdParaAtualizar(poId).orElseThrow(() -> new NotFoundException("Ordem de compra"));
        if (!po.isErpManaged()) throw new BusinessRuleException("Esta PO não é gerida por ERP.");
        if (po.getStatus() != PoStatus.AGUARDANDO_APROVACAO) return po; // outro callback já decidiu entretanto

        Instant agora = Instant.now();
        if (aprovado) {
            po.setStatus(PoStatus.APROVADA);
            po.setApprovedAt(agora);
        } else {
            po.setStatus(PoStatus.REJEITADA);
            po.setRejectedAt(agora);
            po.setRejectionReason(motivo == null || motivo.isBlank() ? "Rejeitada pelo workflow de aprovação do ERP." : motivo);
        }
        if (erpExternalId != null && !erpExternalId.isBlank()) po.setErpExternalId(erpExternalId);

        erpSyncLogRepository.save(new ErpSyncLog(poId, ErpSyncDirection.INBOUND, "approval_decided", ErpSyncStatus.SUCCESS,
                erpExternalId == null || erpExternalId.isBlank() ? null : erpExternalId, null));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("erpExternalId", erpExternalId == null || erpExternalId.isBlank() ? null : erpExternalId);
        detail.put("motivo", motivo == null || motivo.isBlank() ? null : motivo);
        auditService.record(new AuditService.Entry(ATOR_ERP, aprovado ? "PO_APROVADA_ERP" : "PO_REJEITADA_ERP",
                "PurchaseOrder", po.getId(), po.getReference(), detail));

        notificationService.poAprovadaOuRejeitada(po);
        if (aprovado) notificationService.poRecebidaPeloFornecedor(po);
        // NÃO republica purchase_order.approved no eventBus — o ERP já sabe da sua
        // própria decisão; republicá-la criaria um loop entre os dois sistemas.
        return po;
    }

    /**
     * Aplica a confirmação de pagamento do ERP a uma PO erpManaged — o dinheiro
     * não passa pelo KIXIMA, só a confirmação. O bloqueio da linha da PO fecha
     * a janela TOCTOU do Node (duas confirmações concorrentes: a segunda espera
     * e encontra a PO já PAGA).
     */
    @Transactional
    public PurchaseOrder aplicarPagamentoErp(String poId, String erpExternalId, BigDecimal valorPago, Instant pagoEm) {
        PurchaseOrder po = purchaseOrderRepository.findByIdParaAtualizar(poId).orElseThrow(() -> new NotFoundException("Ordem de compra"));
        if (!po.isErpManaged()) throw new BusinessRuleException("Esta PO não é gerida por ERP.");
        if (po.getStatus() == PoStatus.PAGA) return po;
        // Pela tabela, não pela relação inversa `po.invoice`: numa sessão que já tinha a PO
        // carregada antes de a fatura nascer (aceitação e confirmação no mesmo contexto), a
        // relação lazy ficaria a null mesmo com a fatura já gravada.
        Invoice invoice = invoiceRepository.findByPurchaseOrderId(poId).orElse(null);
        if (invoice == null) {
            throw new BusinessRuleException("Esta PO ainda não tem fatura — o fornecedor precisa de a aceitar primeiro.");
        }
        if (po.getStatus() != PoStatus.AGUARDANDO_PAGAMENTO) {
            throw new ConflictException("PO no estado \"" + po.getStatus() + "\" não pode receber confirmação de pagamento.");
        }

        Company supplierCompany = companyRepository.findById(po.getSupplierCompanyId()).orElse(null);
        Instant agora = Instant.now();
        // O pagamento É o documento "RC" (Recibo) da AGT — mesma cadeia de integridade
        // de qualquer outro pagamento, independentemente do canal.
        var certificacao = faturacaoService.atribuir(pagoEm == null ? agora : pagoEm, invoice.getAmount(),
                faturacaoService.serieReciboDoFornecedor(supplierCompany == null ? null : supplierCompany.getSerieFiscal()),
                supplierCompany == null ? null : supplierCompany.getDataAdesaoFacturacaoElectronica());
        Payment payment = Payment.confirmadoPeloErp(UUID.randomUUID().toString(), invoice.getId(),
                valorPago != null ? valorPago : invoice.getAmount(), invoice.getCurrency(),
                "PAY-ERP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), agora, certificacao);
        paymentRepository.save(payment);

        invoice.setStatus(InvoiceStatus.PAGA);
        po.setStatus(PoStatus.PAGA);
        po.setPaidAt(agora);
        if (erpExternalId != null && !erpExternalId.isBlank()) po.setErpExternalId(erpExternalId);

        // Taxa da plataforma — cobrada ao fornecedor, independentemente de o pagamento
        // ter passado pelo KIXIMA ou ter sido confirmado pelo ERP.
        platformFeeService.createForInvoice(invoice, po.getSupplierCompanyId());

        erpSyncLogRepository.save(new ErpSyncLog(poId, ErpSyncDirection.INBOUND, "payment_confirmed", ErpSyncStatus.SUCCESS,
                erpExternalId == null || erpExternalId.isBlank() ? null : erpExternalId, null));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("po", po.getReference());
        detail.put("valor", ao.kixima.common.Decimais.texto(payment.getAmount()));
        detail.put("erpExternalId", erpExternalId == null || erpExternalId.isBlank() ? null : erpExternalId);
        auditService.record(new AuditService.Entry(ATOR_ERP, "PAGAMENTO_CONFIRMADO_ERP", "Payment", payment.getId(),
                payment.getReference(), detail));

        notificationService.pagamentoProcessado(payment, po);
        // Submissão à Sandbox AGT (não-bloqueante, silenciosa sem credenciais) — depois do commit.
        String paymentIdParaAgt = payment.getId();
        String supplierCompanyIdParaAgt = po.getSupplierCompanyId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                agtSandboxSubmissionService.submeter("RC", paymentIdParaAgt, supplierCompanyIdParaAgt);
            }
        });
        return po;
    }

    // --- Leitura -------------------------------------------------------------

    @Transactional(readOnly = true)
    public PurchaseOrder getPurchaseOrder(String id, CurrentUser user) {
        PurchaseOrder po = purchaseOrderRepository.findById(id).orElseThrow(() -> new NotFoundException("Ordem de compra"));
        // Controlo de acesso multi-tenant: só as empresas envolvidas (ou o Admin do
        // Sistema) podem ver a PO. 404 (não 403) para não revelar a existência.
        if (user != null && user.role() != PersonaRole.ADMIN_SISTEMA) {
            boolean own = po.getBuyerCompanyId().equals(user.companyId()) || po.getSupplierCompanyId().equals(user.companyId());
            if (!own) throw new NotFoundException("Ordem de compra");
        }
        return po;
    }

    @Transactional(readOnly = true)
    public List<PurchaseOrder> listPurchaseOrders(String companyId, PersonaRole role, PoStatus status) {
        return purchaseOrderRepository.findAll(PurchaseOrderSpecifications.paraListagem(companyId, role, status),
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
    }

    // --- 2. Aprovação (Company Admin — ponto único) ---------------------------

    @Transactional
    public PurchaseOrder approvePurchaseOrder(String id, String approverId, String approverCompanyId) {
        PurchaseOrder po = getPurchaseOrder(id, null);
        // Só o Company Admin da empresa COMPRADORA (a que criou a PO) pode aprová-la.
        if (!po.getBuyerCompanyId().equals(approverCompanyId)) {
            throw new ForbiddenException("Só o Company Admin da empresa compradora pode aprovar esta PO.");
        }
        if (po.isCallOff()) {
            throw new BusinessRuleException("Call-offs não passam por aprovação individual.");
        }
        if (po.isErpManaged()) {
            throw new BusinessRuleException(
                    "Esta PO é aprovada através do ERP configurado — a decisão chega automaticamente, não é aprovável manualmente.");
        }
        if (po.getStatus() != PoStatus.AGUARDANDO_APROVACAO) {
            throw new ConflictException("PO no estado \"" + po.getStatus() + "\" não pode ser aprovada.");
        }

        po.setStatus(PoStatus.APROVADA);
        po.setApprovedById(approverId);
        po.setApprovedAt(Instant.now());
        notificationService.poAprovadaOuRejeitada(po);
        notificationService.poRecebidaPeloFornecedor(po);
        // Evento para a integração ERP (não-bloqueante; enviado depois do commit).
        eventBus.publish("purchase_order.approved", EventPayloads.purchaseOrderApproved(po, po.getApprovedAt()),
                "po-approved:" + po.getId(), po.getBuyerCompanyId());
        return po;
    }

    @Transactional
    public PurchaseOrder rejectPurchaseOrder(String id, String approverId, String reason, String approverCompanyId) {
        PurchaseOrder po = getPurchaseOrder(id, null);
        if (!po.getBuyerCompanyId().equals(approverCompanyId)) {
            throw new ForbiddenException("Só o Company Admin da empresa compradora pode rejeitar esta PO.");
        }
        if (po.isCallOff()) {
            throw new BusinessRuleException("Call-offs não passam por aprovação individual.");
        }
        if (po.isErpManaged()) {
            throw new BusinessRuleException(
                    "Esta PO é aprovada através do ERP configurado — a decisão chega automaticamente, não é rejeitável manualmente.");
        }
        if (po.getStatus() != PoStatus.AGUARDANDO_APROVACAO) {
            throw new ConflictException("PO no estado \"" + po.getStatus() + "\" não pode ser rejeitada.");
        }

        po.setStatus(PoStatus.REJEITADA);
        po.setApprovedById(approverId);
        po.setRejectedAt(Instant.now());
        po.setRejectionReason(reason);
        notificationService.poAprovadaOuRejeitada(po);
        return po;
    }

    // --- 3/4. Fornecedor aceita -> gera a fatura ------------------------------

    @Transactional
    public PurchaseOrder acceptPurchaseOrder(String id, String supplierCompanyId) {
        PurchaseOrder po = getPurchaseOrder(id, null);
        if (!po.getSupplierCompanyId().equals(supplierCompanyId)) {
            throw new ForbiddenException("Só o fornecedor da PO pode aceitá-la.");
        }
        if (po.getStatus() != PoStatus.APROVADA) {
            throw new ConflictException("PO no estado \"" + po.getStatus() + "\" não pode ser aceite.");
        }

        Instant acceptedAt = Instant.now();

        if (po.isCallOff()) {
            // Call-off: sem fatura individual nem prazo de 7 dias — a faturação
            // consolida-se periodicamente (ContractService.consolidateContractBilling).
            po.setStatus(PoStatus.EM_EXECUCAO);
            po.setAcceptedAt(acceptedAt);
            return po;
        }

        Instant paymentDueAt = acceptedAt.plus(paymentSlaDays, ChronoUnit.DAYS);
        po.setStatus(PoStatus.AGUARDANDO_PAGAMENTO);
        po.setAcceptedAt(acceptedAt);
        po.setPaymentDueAt(paymentDueAt);

        // A série certificada é do FORNECEDOR (emitente fiscal desta fatura), nunca uma série global.
        Company supplierCompany = po.getSupplierCompany();
        List<PurchaseOrderItem> items = purchaseOrderItemRepository.findByPurchaseOrderIdOrderByIdAsc(po.getId());
        List<TaxService.Line> linhasImposto = items.stream()
                .map(li -> new TaxService.Line(li.getLineTotal(), li.getProduct() == null ? null : li.getProduct().getKind()))
                .toList();
        var iva = taxService.summarize(linhasImposto);

        String reference = referenceCounterService.nextReference("FAT", "invoice");
        var certificacao = faturacaoService.atribuir(acceptedAt, iva.gross(),
                faturacaoService.serieFiscalDoFornecedor(supplierCompany == null ? null : supplierCompany.getSerieFiscal()),
                supplierCompany == null ? null : supplierCompany.getDataAdesaoFacturacaoElectronica());

        Invoice invoice = new Invoice(UUID.randomUUID().toString(), reference, po.getId(), iva.gross(), iva.net(),
                iva.tax(), iva.withheld(), po.getCurrency(), InvoiceStatus.PENDENTE, acceptedAt, paymentDueAt,
                certificacao.serie(), certificacao.numeroNaSerie(), certificacao.hashDocumento(),
                certificacao.hashAnterior(), certificacao.assinadaEm(), null, acceptedAt, acceptedAt);
        invoiceRepository.save(invoice);

        // A referência de pagamento nasce com a fatura, na mesma transação.
        conciliacaoService.atribuirReferencia(invoice);

        List<FaturacaoService.LinhaFatura> linhas = faturacaoService.linhasFaturaAGT(items);
        for (int i = 0; i < linhas.size(); i++) {
            var l = linhas.get(i);
            invoiceLineRepository.save(new InvoiceLine(UUID.randomUUID().toString(), invoice.getId(), i + 1,
                    l.productCode(), l.description(), l.quantity(), l.unitPrice(), l.netAmount(), l.ivaAmount(), acceptedAt));
        }

        notificationService.faturaGerada(invoice, po);
        // Evento para a integração ERP (não-bloqueante; enviado depois do commit).
        eventBus.publish("invoice.issued", EventPayloads.invoiceIssued(invoice, po),
                "invoice-issued:" + invoice.getId(), po.getBuyerCompanyId());

        // Submissão à Sandbox AGT (não-bloqueante, silenciosa sem credenciais) —
        // espelha o `await agtSandboxSubmissionService.submeter(...)` do Node, que só
        // corre DEPOIS do `prisma.$transaction(...)` fechar (documento já comitado).
        // Aqui equivale a correr depois do COMMIT desta transacção Spring — nunca
        // dentro dela, para uma falha de rede nunca poder reverter a fatura já
        // emitida. `afterCommit` não corre em testes com rollback (@Transactional de
        // teste) — correcto: nada foi comitado para submeter.
        String invoiceIdParaAgt = invoice.getId();
        String supplierCompanyIdParaAgt = po.getSupplierCompanyId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                agtSandboxSubmissionService.submeter("FT", invoiceIdParaAgt, supplierCompanyIdParaAgt);
            }
        });

        return po;
    }

    @Transactional
    public PurchaseOrder refusePurchaseOrder(String id, String supplierCompanyId, String reason) {
        PurchaseOrder po = getPurchaseOrder(id, null);
        if (!po.getSupplierCompanyId().equals(supplierCompanyId)) {
            throw new ForbiddenException("Só o fornecedor da PO pode recusá-la.");
        }
        if (po.getStatus() != PoStatus.APROVADA) {
            throw new ConflictException("PO no estado \"" + po.getStatus() + "\" não pode ser recusada.");
        }
        po.setStatus(PoStatus.RECUSADA_FORNECEDOR);
        po.setRefusedAt(Instant.now());
        po.setRefusalReason(reason);
        notificationService.poRecusadaPeloFornecedor(po);
        return po;
    }

    // --- 6. Execução/despacho (só após pagamento confirmado) ------------------

    @Transactional
    public PurchaseOrder dispatchPurchaseOrder(String id, String supplierCompanyId) {
        PurchaseOrder po = getPurchaseOrder(id, null);
        if (!po.getSupplierCompanyId().equals(supplierCompanyId)) {
            throw new ForbiddenException("Só o fornecedor da PO pode despachar a entrega.");
        }
        Set<PoStatus> readyStatuses = po.isCallOff() ? Set.of(PoStatus.EM_EXECUCAO) : Set.of(PoStatus.PAGA);
        if (!readyStatuses.contains(po.getStatus())) {
            throw new BusinessRuleException(po.isCallOff()
                    ? "Call-off precisa estar em execução antes do despacho."
                    : "O pagamento precisa estar confirmado antes de despachar a entrega.");
        }
        po.setStatus(PoStatus.EM_EXECUCAO);
        po.setDispatchedAt(Instant.now());
        notificationService.entregaDespachada(po);
        return po;
    }

    @Transactional
    public PurchaseOrder markDelivered(String id, String supplierCompanyId) {
        PurchaseOrder po = getPurchaseOrder(id, null);
        if (!po.getSupplierCompanyId().equals(supplierCompanyId)) {
            throw new ForbiddenException("Só o fornecedor da PO pode marcar como entregue.");
        }
        if (po.getStatus() != PoStatus.EM_EXECUCAO) {
            throw new ConflictException("PO no estado \"" + po.getStatus() + "\" não pode ser marcada como entregue.");
        }
        po.setStatus(PoStatus.ENTREGUE);
        po.setDeliveredAt(Instant.now());
        notificationService.poEntregue(po);
        return po;
    }

    // --- 7. Receção (Comprador) ------------------------------------------------

    public record ConfirmacaoRececao(boolean conforme, String notes) {
    }

    @Transactional
    public PurchaseOrder confirmReception(String id, String buyerCompanyId, ConfirmacaoRececao body) {
        PurchaseOrder po = getPurchaseOrder(id, null);
        if (!po.getBuyerCompanyId().equals(buyerCompanyId)) {
            throw new ForbiddenException("Só o comprador da PO pode confirmar a receção.");
        }
        if (po.getStatus() != PoStatus.ENTREGUE && po.getStatus() != PoStatus.EM_EXECUCAO) {
            throw new ConflictException("PO no estado \"" + po.getStatus() + "\" não pode ter receção confirmada.");
        }

        String receptionStatus = body.conforme() ? "Conforme" : (body.notes() != null ? body.notes() : "Com Divergência");
        po.setStatus(body.conforme() ? PoStatus.RECEBIDA_CONFORME : PoStatus.RECEBIDA_COM_DIVERGENCIA);
        po.setReceivedAt(Instant.now());
        po.setReceptionStatus(receptionStatus);

        // Evento para a integração ERP (receção de mercadoria) — não-bloqueante; enviado depois do commit.
        eventBus.publish("goods.received", EventPayloads.goodsReceived(po, po.getReceivedAt()),
                "goods-received:" + po.getId(), po.getBuyerCompanyId());

        if (!body.conforme()) {
            notificationService.rececaoComDivergencia(po);
            return po;
        }

        // 8. Sistema fecha a ordem automaticamente quando a receção é conforme.
        po.setStatus(PoStatus.CONCLUIDA);
        notificationService.poRecebidaConforme(po);
        return po;
    }

    // --- 7b. Resolução de divergências ------------------------------------------

    private static final Map<String, PoStatus> DIVERGENCE_OUTCOMES = Map.of(
            "ACEITE", PoStatus.CONCLUIDA, "REPOSICAO", PoStatus.EM_EXECUCAO);

    public record ResolucaoDivergencia(String outcome, String notes) {
    }

    @Transactional
    public PurchaseOrder resolveDivergence(String id, String buyerCompanyId, ResolucaoDivergencia body) {
        PurchaseOrder po = getPurchaseOrder(id, null);
        if (!po.getBuyerCompanyId().equals(buyerCompanyId)) {
            throw new ForbiddenException("Só o comprador da PO pode resolver a divergência.");
        }
        if (po.getStatus() != PoStatus.RECEBIDA_COM_DIVERGENCIA) {
            throw new ConflictException("PO no estado \"" + po.getStatus() + "\" não tem divergência por resolver.");
        }
        PoStatus alvo = DIVERGENCE_OUTCOMES.get(body.outcome());
        if (alvo == null) {
            throw new BusinessRuleException("Desfecho inválido — use ACEITE ou REPOSICAO.");
        }

        po.setStatus(alvo);
        po.setDivergenceResolution(body.outcome());
        po.setDivergenceResolutionNotes(body.notes());
        po.setDivergenceResolvedAt(Instant.now());
        notificationService.divergenciaResolvida(po);
        return po;
    }
}
