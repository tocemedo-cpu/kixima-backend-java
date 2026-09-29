package ao.kixima.po;

import ao.kixima.catalog.CatalogService;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.contract.ContractRepository;
import ao.kixima.creditnote.CreditNoteRepository;
import ao.kixima.creditnote.dto.CreditNoteDto;
import ao.kixima.invoice.Invoice;
import ao.kixima.invoice.InvoiceLine;
import ao.kixima.invoice.dto.InvoiceDto;
import ao.kixima.invoice.dto.InvoiceLineDto;
import ao.kixima.payment.PaymentRepository;
import ao.kixima.payment.dto.PaymentDto;
import ao.kixima.po.dto.PurchaseOrderDto;
import ao.kixima.po.dto.PurchaseOrderItemDto;
import ao.kixima.security.CurrentUser;
import ao.kixima.security.PersonaRole;
import ao.kixima.user.User;
import ao.kixima.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * Constrói as respostas JSON das ordens de compra com a MESMA forma que cada
 * função do poService.js devolve (o `include` de cada uma), e fá-lo dentro de
 * uma transação de leitura própria: as relações lazy (items, invoice, empresas)
 * não sobrevivem ao fim da transação do serviço — fora dos testes (que correm
 * numa transação só) era um 500 "no Session", apanhado pelo replay de contrato
 * do M7. Reler a PO aqui é o equivalente do `findUnique({ include })` do Prisma.
 */
@Service
public class PoDtoService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PaymentRepository paymentRepository;
    private final CreditNoteRepository creditNoteRepository;
    private final UserRepository userRepository;
    private final ContractRepository contractRepository;
    private final CatalogService catalogService;
    private final ObjectMapper objectMapper;

    public PoDtoService(PurchaseOrderRepository purchaseOrderRepository, PaymentRepository paymentRepository,
                        CreditNoteRepository creditNoteRepository, UserRepository userRepository, ContractRepository contractRepository,
                        CatalogService catalogService, ObjectMapper objectMapper) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.paymentRepository = paymentRepository;
        this.creditNoteRepository = creditNoteRepository;
        this.userRepository = userRepository;
        this.contractRepository = contractRepository;
        this.catalogService = catalogService;
        this.objectMapper = objectMapper;
    }

    /** createPurchaseOrder: `include: { items: true }`. */
    @Transactional(readOnly = true)
    public PurchaseOrderDto criada(String id) {
        return PurchaseOrderDto.de(carregar(id));
    }

    /** listPurchaseOrders: `include: { items: true, invoice: { include: { payment: true } } }`, por createdAt desc. */
    @Transactional(readOnly = true)
    public List<PurchaseOrderDto> listagem(String companyId, PersonaRole role, PoStatus status) {
        return purchaseOrderRepository.findAll(PurchaseOrderSpecifications.paraListagem(companyId, role, status),
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"))
                .stream().map(po -> PurchaseOrderDto.de(po, itens(po, false), faturaResumida(po.getInvoice()), null, null, null, null, null))
                .toList();
    }

    /**
     * getPurchaseOrder: items.product, invoice.{payment(+processedByName), creditNotes, lines},
     * buyerCompany/supplierCompany (COMPANY_FIELDS), createdBy/approvedBy (nome), contract.reference.
     * Mesmo controlo de acesso do serviço: 404 (não 403) para quem é alheio à PO.
     */
    @Transactional(readOnly = true)
    public PurchaseOrderDto detalhe(String id, CurrentUser user) {
        PurchaseOrder po = carregar(id);
        if (user != null && user.role() != PersonaRole.ADMIN_SISTEMA) {
            boolean own = po.getBuyerCompanyId().equals(user.companyId()) || po.getSupplierCompanyId().equals(user.companyId());
            if (!own) throw new NotFoundException("Ordem de compra");
        }
        return PurchaseOrderDto.de(po, itens(po, true), faturaCompleta(po.getInvoice()),
                PurchaseOrderDto.CompanyRef.de(po.getBuyerCompany()), PurchaseOrderDto.CompanyRef.de(po.getSupplierCompany()),
                nome(po.getCreatedById()), nome(po.getApprovedById()),
                po.getContractId() == null ? null : contractRepository.findById(po.getContractId())
                        .map(c -> new PurchaseOrderDto.ContractRef(c.getReference())).orElse(null));
    }

    private PurchaseOrder carregar(String id) {
        return purchaseOrderRepository.findById(id).orElseThrow(() -> new NotFoundException("Ordem de compra"));
    }

    private List<PurchaseOrderItemDto> itens(PurchaseOrder po, boolean comProduto) {
        return po.getItems().stream()
                .map(i -> PurchaseOrderItemDto.de(i, comProduto && i.getProduct() != null ? catalogService.toDto(i.getProduct(), null, false) : null))
                .toList();
    }

    private InvoiceDto faturaResumida(Invoice invoice) {
        if (invoice == null) return null;
        PaymentDto payment = paymentRepository.findByInvoiceId(invoice.getId()).map(p -> PaymentDto.de(p, null, null)).orElse(null);
        return InvoiceDto.de(invoice, null, null, payment, null, null);
    }

    private InvoiceDto faturaCompleta(Invoice invoice) {
        if (invoice == null) return null;
        PaymentDto payment = paymentRepository.findByInvoiceId(invoice.getId())
                .map(p -> PaymentDto.de(p, null, null, p.getProcessedById() == null ? null
                        : userRepository.findById(p.getProcessedById()).map(User::getName).orElse(null)))
                .orElse(null);
        List<CreditNoteDto> notas = creditNoteRepository.findByInvoiceIdOrderByIssuedAtAsc(invoice.getId()).stream()
                .map(c -> CreditNoteDto.de(c, objectMapper)).toList();
        List<InvoiceLineDto> linhas = invoice.getLines().stream()
                .sorted(Comparator.comparingInt(InvoiceLine::getLineNumber)).map(InvoiceLineDto::de).toList();
        return InvoiceDto.de(invoice, null, null, payment, notas, linhas);
    }

    private PurchaseOrderDto.NameRef nome(String userId) {
        if (userId == null) return null;
        return userRepository.findById(userId).map(u -> new PurchaseOrderDto.NameRef(u.getName())).orElse(null);
    }
}
