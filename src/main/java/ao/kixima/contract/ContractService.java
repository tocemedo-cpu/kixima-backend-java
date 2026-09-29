package ao.kixima.contract;

import ao.kixima.agt.AgtSandboxSubmissionService;
import ao.kixima.common.error.BusinessRuleException;
import ao.kixima.common.error.ForbiddenException;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.common.reference.ReferenceCounterService;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyRepository;
import ao.kixima.conciliacao.ConciliacaoService;
import ao.kixima.contract.dto.ContractDto;
import ao.kixima.contract.dto.CreateContractRequest;
import ao.kixima.faturacao.FaturacaoService;
import ao.kixima.invoice.Invoice;
import ao.kixima.invoice.InvoiceLine;
import ao.kixima.invoice.InvoiceLineRepository;
import ao.kixima.invoice.InvoiceRepository;
import ao.kixima.invoice.InvoiceStatus;
import ao.kixima.notification.NotificationChannel;
import ao.kixima.notification.NotificationService;
import ao.kixima.notification.NotificationType;
import ao.kixima.plan.PlanFeatureFlag;
import ao.kixima.plan.PlanService;
import ao.kixima.po.PoStatus;
import ao.kixima.po.PurchaseOrder;
import ao.kixima.po.PurchaseOrderItem;
import ao.kixima.po.PurchaseOrderItemRepository;
import ao.kixima.po.PurchaseOrderRepository;
import ao.kixima.po.dto.PurchaseOrderDto;
import ao.kixima.security.CurrentUser;
import ao.kixima.security.PersonaRole;
import ao.kixima.tax.TaxService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Espelha backend/src/services/contractService.js — contratos-quadro e
 * call-offs (secção 5 da especificação).
 */
@Service
public class ContractService {

    /** Estados das call-offs elegíveis para a fatura consolidada. */
    private static final Set<PoStatus> ESTADOS_POR_FATURAR =
            Set.of(PoStatus.ENTREGUE, PoStatus.RECEBIDA_CONFORME, PoStatus.EM_EXECUCAO, PoStatus.APROVADA);

    private final ContractRepository contractRepository;
    private final CompanyRepository companyRepository;
    private final PlanService planService;
    private final ReferenceCounterService referenceCounterService;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final TaxService taxService;
    private final FaturacaoService faturacaoService;
    private final ConciliacaoService conciliacaoService;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceLineRepository invoiceLineRepository;
    private final NotificationService notificationService;
    private final AgtSandboxSubmissionService agtSandboxSubmissionService;

    @PersistenceContext
    private EntityManager entityManager;

    public ContractService(ContractRepository contractRepository, CompanyRepository companyRepository, PlanService planService,
                           ReferenceCounterService referenceCounterService, PurchaseOrderRepository purchaseOrderRepository,
                           PurchaseOrderItemRepository purchaseOrderItemRepository, TaxService taxService,
                           FaturacaoService faturacaoService, ConciliacaoService conciliacaoService, InvoiceRepository invoiceRepository,
                           InvoiceLineRepository invoiceLineRepository, NotificationService notificationService,
                           AgtSandboxSubmissionService agtSandboxSubmissionService) {
        this.contractRepository = contractRepository;
        this.companyRepository = companyRepository;
        this.planService = planService;
        this.referenceCounterService = referenceCounterService;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderItemRepository = purchaseOrderItemRepository;
        this.taxService = taxService;
        this.faturacaoService = faturacaoService;
        this.conciliacaoService = conciliacaoService;
        this.invoiceRepository = invoiceRepository;
        this.invoiceLineRepository = invoiceLineRepository;
        this.notificationService = notificationService;
        this.agtSandboxSubmissionService = agtSandboxSubmissionService;
    }

    /**
     * O contrato-quadro está no plano Pro — mas de QUEM? É um instrumento de
     * COMPRA: é a operadora (cliente) que estabelece condições e emite
     * call-offs contra elas. Exigi-lo também ao fornecedor bloquearia uma
     * operadora Pro de contratar um fornecedor pequeno. A guarda vale também
     * quando é o Admin do Sistema a criar: a regra é sobre o que o plano do
     * cliente inclui, não sobre quem carregou no botão.
     */
    private void exigirPlanoParaContrato(String clientCompanyId) {
        Company cliente = companyRepository.findById(clientCompanyId).orElseThrow(() -> new NotFoundException("Empresa cliente"));
        planService.assertFeature(cliente, PlanFeatureFlag.FRAMEWORK_CONTRACTS, "Contratos-quadro");
    }

    @Transactional
    public ContractDto createContract(CreateContractRequest body, CurrentUser actor) {
        // Um COMPANY_ADMIN só pode criar um contrato-quadro EM NOME DA SUA PRÓPRIA
        // empresa-cliente — sem isto, qualquer empresa podia declarar um contrato
        // (e o desconto de aprovação automática de call-offs que vem com ele) entre
        // DUAS empresas terceiras. Só o Admin do Sistema fica isento.
        if (actor != null && actor.role() != PersonaRole.ADMIN_SISTEMA && !body.clientCompanyId().equals(actor.companyId())) {
            throw new ForbiddenException("Só pode criar um contrato-quadro em nome da sua própria empresa (como cliente).");
        }
        exigirPlanoParaContrato(body.clientCompanyId());

        String reference = referenceCounterService.nextReference("CTR", "contract");
        Contract contract = contractRepository.save(new Contract(UUID.randomUUID().toString(), reference, body.clientCompanyId(),
                body.supplierCompanyId(), body.categoriesCovered(), body.totalValue(), body.moedaOuAoa(), body.billingPeriodicity(),
                body.paymentTermDays(), body.validFrom(), body.validUntil(), Instant.now()));
        // Relações só-leitura (clientCompany/supplierCompany) ficam por carregar numa entidade
        // acabada de persistir — reler garante que a listagem na mesma sessão as devolve.
        entityManager.flush();
        entityManager.refresh(contract);
        return ContractDto.de(contract, false, null);
    }

    @Transactional(readOnly = true)
    public List<ContractDto> listContractsForCompany(String companyId) {
        return contractRepository.findDaEmpresa(companyId).stream().map(c -> ContractDto.de(c, true, null)).toList();
    }

    /** Admin do Sistema KIXIMA não pertence a nenhuma empresa transacionadora — vê todos os contratos-quadro. */
    @Transactional(readOnly = true)
    public List<ContractDto> listAllContracts() {
        return contractRepository.findAllByOrderByCreatedAtDesc().stream().map(c -> ContractDto.de(c, true, null)).toList();
    }

    private Contract contratoComAcesso(String id, CurrentUser user) {
        Contract contract = contractRepository.findById(id).orElseThrow(() -> new NotFoundException("Contrato"));
        // Controlo de acesso multi-tenant: só as empresas do contrato (ou o Admin do
        // Sistema) podem vê-lo. Devolve 404 para não revelar a existência.
        if (user != null && user.role() != PersonaRole.ADMIN_SISTEMA) {
            boolean own = contract.getClientCompanyId().equals(user.companyId()) || contract.getSupplierCompanyId().equals(user.companyId());
            if (!own) throw new NotFoundException("Contrato");
        }
        return contract;
    }

    @Transactional(readOnly = true)
    public ContractDto getContract(String id, CurrentUser user) {
        Contract contract = contratoComAcesso(id, user);
        List<PurchaseOrderDto> callOffs = purchaseOrderRepository.findByContractIdOrderByCreatedAtDesc(id).stream()
                .map(PurchaseOrderDto::escalar).toList();
        return ContractDto.de(contract, false, callOffs);
    }

    /**
     * Deteção automática de Call-off: dado um comprador/cliente, fornecedor e as
     * categorias dos itens do checkout, procura um contrato-quadro ATIVO e válido
     * que cubra o fornecedor e todas as categorias. Usado por PoService no checkout.
     */
    @Transactional(readOnly = true)
    public Optional<Contract> findActiveContractForOrder(String clientCompanyId, String supplierCompanyId, List<String> categories) {
        Instant now = Instant.now();
        return contractRepository
                .findByClientCompanyIdAndSupplierCompanyIdAndStatusAndValidFromLessThanEqualAndValidUntilGreaterThanEqual(
                        clientCompanyId, supplierCompanyId, ContractStatus.ATIVO, now, now)
                .stream().filter(c -> c.cobre(categories)).findFirst();
    }

    /** `usedValue: { increment: net }` no checkout de uma call-off (poService.js). */
    @Transactional
    public void consumir(String contractId, java.math.BigDecimal liquido) {
        contractRepository.findById(contractId).ifPresent(c -> c.consumir(liquido));
    }

    /**
     * Faturamento consolidado periódico: soma as call-offs "por faturar" de um
     * contrato e gera uma única fatura com o prazo de pagamento do contrato.
     * Fatura, linhas, referência de pagamento e marcação das call-offs vivem
     * na MESMA transação — a numeração certificada só se devolve se algo falhar.
     */
    @Transactional
    public Invoice consolidateContractBilling(String contractId, CurrentUser actor) {
        // Mesmo controlo de posse de getContract: 404 em vez de 403 para não revelar
        // a existência do contrato a quem não é parte dele.
        Contract contract = contratoComAcesso(contractId, actor);

        List<PurchaseOrder> pendingCallOffs = purchaseOrderRepository
                .findByContractIdAndIsCallOffTrueAndStatusInAndConsolidatedInvoiceIdIsNull(contractId, ESTADOS_POR_FATURAR);
        if (pendingCallOffs.isEmpty()) {
            throw new BusinessRuleException("Não há call-offs pendentes de faturação para este contrato.");
        }

        List<PurchaseOrderItem> items = new ArrayList<>();
        for (PurchaseOrder po : pendingCallOffs) {
            items.addAll(purchaseOrderItemRepository.findByPurchaseOrderIdOrderByIdAsc(po.getId()));
        }
        // IVA (lei angolana) por linha de todos os call-offs consolidados.
        var iva = taxService.summarize(items.stream()
                .map(li -> new TaxService.Line(li.getLineTotal(), li.getProduct() == null ? null : li.getProduct().getKind()))
                .toList());
        Instant agora = Instant.now();
        Instant dueAt = agora.plus(contract.getPaymentTermDays(), ChronoUnit.DAYS);

        String reference = referenceCounterService.nextReference("FAT", "invoice");

        // A série certificada é do FORNECEDOR (emitente fiscal desta fatura), nunca uma série global da KIXIMA.
        Company supplierCompany = companyRepository.findById(contract.getSupplierCompanyId()).orElse(null);
        var certificacao = faturacaoService.atribuir(agora, iva.gross(),
                faturacaoService.serieFiscalDoFornecedor(supplierCompany == null ? null : supplierCompany.getSerieFiscal()),
                supplierCompany == null ? null : supplierCompany.getDataAdesaoFacturacaoElectronica());

        Invoice invoice = new Invoice(UUID.randomUUID().toString(), reference, null, iva.gross(), iva.net(), iva.tax(), iva.withheld(),
                contract.getCurrency(), InvoiceStatus.PENDENTE, agora, dueAt, certificacao.serie(), certificacao.numeroNaSerie(),
                certificacao.hashDocumento(), certificacao.hashAnterior(), certificacao.assinadaEm(), null, agora, agora);
        invoice.consolidarCallOffs(contract, pendingCallOffs.stream().map(PurchaseOrder::getId).toList());
        invoiceRepository.save(invoice);

        // Linhas do documento fiscal, de todos os call-offs consolidados.
        List<FaturacaoService.LinhaFatura> linhas = faturacaoService.linhasFaturaAGT(items);
        for (int i = 0; i < linhas.size(); i++) {
            var l = linhas.get(i);
            invoiceLineRepository.save(new InvoiceLine(UUID.randomUUID().toString(), invoice.getId(), i + 1,
                    l.productCode(), l.description(), l.quantity(), l.unitPrice(), l.netAmount(), l.ivaAmount(), agora));
        }

        for (PurchaseOrder po : pendingCallOffs) {
            po.setPaymentDueAt(dueAt);
            po.setConsolidatedInvoiceId(invoice.getId());
        }

        conciliacaoService.atribuirReferencia(invoice);

        notificationService.notifyUsersByRole(contract.getClientCompanyId(), List.of(PersonaRole.FINANCEIRO), NotificationType.FATURA_GERADA,
                "Fatura consolidada de call-offs",
                "Fatura consolidada " + invoice.getReference() + " gerada para o contrato " + contract.getReference()
                        + ", no valor de " + invoice.getAmount().toPlainString() + " " + contract.getCurrency() + ".",
                NotificationChannel.IN_APP_EMAIL, "Invoice", invoice.getId());

        // Submissão à Sandbox AGT depois do COMMIT (mesma regra de PoService.acceptPurchaseOrder).
        String invoiceIdParaAgt = invoice.getId();
        String supplierCompanyIdParaAgt = contract.getSupplierCompanyId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                agtSandboxSubmissionService.submeter("FT", invoiceIdParaAgt, supplierCompanyIdParaAgt);
            }
        });

        return invoice;
    }
}
