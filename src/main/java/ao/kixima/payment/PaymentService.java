package ao.kixima.payment;

import ao.kixima.agt.AgtPayloadService;
import ao.kixima.agt.AgtSandboxSubmissionService;
import ao.kixima.audit.Actor;
import ao.kixima.audit.AuditService;
import ao.kixima.catalog.UploadFilters;
import ao.kixima.common.error.AppException;
import ao.kixima.common.error.ConflictException;
import ao.kixima.common.error.ForbiddenException;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.common.error.ValidationException;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyRepository;
import ao.kixima.faturacao.FaturacaoService;
import ao.kixima.invoice.Invoice;
import ao.kixima.invoice.InvoiceRepository;
import ao.kixima.invoice.InvoiceStatus;
import ao.kixima.invoice.dto.InvoiceDto;
import ao.kixima.messaging.EventBus;
import ao.kixima.messaging.EventPayloads;
import ao.kixima.notification.NotificationService;
import ao.kixima.payment.dto.PaymentDto;
import ao.kixima.po.PoStatus;
import ao.kixima.po.PurchaseOrder;
import ao.kixima.po.PurchaseOrderRepository;
import ao.kixima.security.CurrentUser;
import ao.kixima.storage.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Espelha backend/src/services/paymentService.js — passo 5 do fluxo: o
 * Financeiro valida e paga com fundos do cliente. Executa, não decide (a
 * decisão foi do Company Admin no passo 2). O comprovativo é OBRIGATÓRIO:
 * sem ele o "pago" seria só uma palavra.
 *
 * Transações, como no Node: o pagamento nasce numa transação própria
 * (recibo certificado + fatura/PO PAGA + taxa da plataforma + auditoria);
 * o que vem DEPOIS (notificação, evento ERP, reenvio do FT à AGT, RC à
 * sandbox) nunca bloqueia nem desfaz um pagamento já comitado — o dinheiro
 * já saiu, uma recusa de paperwork não desfaz isso.
 *
 * Duas formas de fatura: a de UMA PO ({@code purchaseOrderId}) e a
 * consolidada de call-offs de um contrato-quadro ({@code contractId} +
 * {@code consolidatedPoIds}, sem PO) — a posse vem da PO ou do contrato.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final InvoiceRepository invoiceRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final CompanyRepository companyRepository;
    private final StorageService storageService;
    private final FaturacaoService faturacaoService;
    private final PlatformFeeService platformFeeService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final EventBus eventBus;
    private final AgtPayloadService agtPayloadService;
    private final AgtSandboxSubmissionService agtSandboxSubmissionService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public PaymentService(PaymentRepository paymentRepository, InvoiceRepository invoiceRepository,
                           PurchaseOrderRepository purchaseOrderRepository,
                           CompanyRepository companyRepository, StorageService storageService, FaturacaoService faturacaoService,
                           PlatformFeeService platformFeeService, AuditService auditService,
                           NotificationService notificationService, EventBus eventBus, AgtPayloadService agtPayloadService,
                           AgtSandboxSubmissionService agtSandboxSubmissionService, ObjectMapper objectMapper,
                           PlatformTransactionManager transactionManager) {
        this.paymentRepository = paymentRepository;
        this.invoiceRepository = invoiceRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.companyRepository = companyRepository;
        this.storageService = storageService;
        this.faturacaoService = faturacaoService;
        this.platformFeeService = platformFeeService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.eventBus = eventBus;
        this.agtPayloadService = agtPayloadService;
        this.agtSandboxSubmissionService = agtSandboxSubmissionService;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public List<InvoiceDto> listPendingInvoices(String buyerCompanyId) {
        return tx.execute(s -> invoiceRepository.findPendentesDoComprador(buyerCompanyId, InvoiceStatus.PENDENTE).stream()
                .map(i -> InvoiceDto.de(i, true)).toList());
    }

    public List<PaymentDto> listPaymentHistory(String buyerCompanyId) {
        return tx.execute(s -> paymentRepository.findHistoricoDoComprador(buyerCompanyId).stream()
                .map(p -> PaymentDto.de(p, InvoiceDto.de(p.getInvoice(), true), null)).toList());
    }

    private record FaturaAPagar(String reference, String purchaseOrderId, List<String> consolidatedPoIds, String buyerCompanyId,
                                String supplierCompanyId, String serieFiscal, Instant dataAdesao) {
    }

    public PaymentDto processPayment(String invoiceId, CurrentUser user, MultipartFile proof, Actor actor) {
        if (proof == null || proof.isEmpty()) {
            throw new ValidationException("Anexe o comprovativo da transferência (PDF ou imagem) para confirmar o pagamento.");
        }
        String tipo = proof.getContentType();
        boolean valido = tipo != null && (tipo.matches("^image/(png|jpe?g|webp|gif)$") || tipo.equals("application/pdf"));
        if (!valido) throw new ValidationException("Documento inválido — use PDF ou imagem (PNG/JPG).");
        UploadFilters.tamanho(proof, UploadFilters.LIMITE_DOCUMENTO); // 10MB do uploadDocuments — DEPOIS do tipo, como o fileFilter do multer

        FaturaAPagar fatura = tx.execute(s -> {
            Invoice invoice = invoiceRepository.findById(invoiceId).orElseThrow(() -> new NotFoundException("Fatura"));
            if (invoice.getStatus() != InvoiceStatus.PENDENTE) {
                throw new ConflictException("Fatura no estado \"" + invoice.getStatus() + "\" não pode ser paga.");
            }
            // `purchaseOrder?.buyerCompanyId ?? contract?.clientCompanyId` — na fatura consolidada de call-offs a posse é do contrato.
            String ownerCompanyId = invoice.buyerCompanyId();
            if (ownerCompanyId == null || !ownerCompanyId.equals(user.companyId())) {
                throw new ForbiddenException("Só pode pagar faturas da sua própria empresa.");
            }
            String supplierId = invoice.supplierCompanyId();
            Company supplier = supplierId == null ? null : companyRepository.findById(supplierId).orElse(null);
            return new FaturaAPagar(invoice.getReference(), invoice.getPurchaseOrderId(), List.copyOf(invoice.getConsolidatedPoIds()),
                    ownerCompanyId, supplierId,
                    supplier == null ? null : supplier.getSerieFiscal(),
                    supplier == null ? null : supplier.getDataAdesaoFacturacaoElectronica());
        });

        // Guarda o comprovativo antes da transação (o upload não é transacional).
        String proofUrl;
        try {
            proofUrl = storageService.saveFile(proof.getBytes(), proof.getOriginalFilename(), tipo, "comprovativo-" + fatura.reference(), "proofs");
        } catch (IOException e) {
            throw new IllegalStateException("Falha a ler o comprovativo enviado.", e);
        }
        String proofName = proof.getOriginalFilename() == null || proof.getOriginalFilename().isBlank() ? "comprovativo" : proof.getOriginalFilename();

        Payment payment = tx.execute(s -> {
            Invoice invoice = invoiceRepository.findById(invoiceId).orElseThrow(() -> new NotFoundException("Fatura"));
            Instant agora = Instant.now();
            // O pagamento É o documento "RC" da AGT — série e cadeia próprias do MESMO fornecedor que emitiu a fatura.
            FaturacaoService.Certificacao certificacao = faturacaoService.atribuir(agora, invoice.getAmount(),
                    faturacaoService.serieReciboDoFornecedor(fatura.serieFiscal()), fatura.dataAdesao());

            Payment criado = new Payment(UUID.randomUUID().toString(), invoiceId, invoice.getAmount(), invoice.getCurrency(), user.id(),
                    "PAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), proofUrl, proofName, agora, certificacao);
            paymentRepository.save(criado);

            invoice.setStatus(InvoiceStatus.PAGA);
            if (fatura.purchaseOrderId() != null) {
                PurchaseOrder po = invoice.getPurchaseOrder();
                po.setStatus(PoStatus.PAGA);
                po.setPaidAt(agora);
            } else if (!fatura.consolidatedPoIds().isEmpty()) {
                // `updateMany({ where: { id: { in: consolidatedPoIds } }, data: { paidAt } })` — só a data; o
                // estado das call-offs não muda (uma fatura consolidada cobre call-offs em execução/entregues).
                for (PurchaseOrder callOff : purchaseOrderRepository.findAllById(fatura.consolidatedPoIds())) {
                    callOff.setPaidAt(agora);
                }
            }

            // Taxa da plataforma (KIXIMA) — à parte da PO/Fatura, cobrada ao fornecedor.
            if (fatura.supplierCompanyId() != null) platformFeeService.createForInvoice(invoice, fatura.supplierCompanyId());

            // Auditoria DENTRO da transação: um pagamento sem registo não existe.
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("fatura", invoice.getReference());
            detail.put("valor", ao.kixima.common.Decimais.texto(invoice.getAmount()));
            detail.put("moeda", invoice.getCurrency());
            detail.put("comprovativo", proofName);
            auditService.record(new AuditService.Entry(actor != null ? actor : new Actor(user.id(), user.name(), null, user.companyId(), null),
                    "PAGAMENTO_EXECUTADO", "Payment", criado.getId(), criado.getReference(), detail));
            return criado;
        });

        Invoice invoiceComPo = tx.execute(s -> {
            Invoice i = invoiceRepository.findById(invoiceId).orElseThrow();
            if (i.getPurchaseOrder() != null) i.getPurchaseOrder().getReference();
            return i;
        });
        // Como no Node, só a fatura de UMA PO avisa o fornecedor ("pagamento da PO X") — a consolidada não tem PO.
        if (fatura.purchaseOrderId() != null) notificationService.pagamentoProcessado(payment, invoiceComPo.getPurchaseOrder());

        eventBus.publish("payment.completed", EventPayloads.paymentCompleted(payment, invoiceComPo),
                "payment-completed:" + payment.getId(), fatura.buyerCompanyId());

        Object agtInvoiceResubmission = null;
        if (fatura.supplierCompanyId() != null) {
            agtInvoiceResubmission = reenviarFtAposPagamento(invoiceId, fatura.supplierCompanyId());
            agtSandboxSubmissionService.submeter("RC", payment.getId(), fatura.supplierCompanyId());
        }
        return PaymentDto.de(payment, null, agtInvoiceResubmission);
    }

    /**
     * Reenvio deliberado e VISÍVEL do FT à AGT ao confirmar o pagamento — a
     * AGT pode recusá-lo (documentNo repetido) e é exactamente isso que se
     * quer ver, não esconder. O resultado da ÚLTIMA tentativa fica na fatura.
     */
    private Map<String, Object> reenviarFtAposPagamento(String invoiceId, String supplierCompanyId) {
        Map<String, Object> resultado = new LinkedHashMap<>();
        String requestId = null;
        String resultCode = null;
        String erroJson = null;
        String estadoJson = null;
        try {
            AgtPayloadService.ResultadoSubmissao r = agtPayloadService.submeterFatura(invoiceId, supplierCompanyId);
            resultado.put("sucesso", true);
            resultado.put("payload", r.payload());
            resultado.put("resposta", r.resposta());
            resultado.put("estado", r.estado());
            Object rid = r.resposta() == null ? null : r.resposta().get("requestID");
            Object rc = r.resposta() == null ? null : r.resposta().get("resultCode");
            requestId = rid == null ? null : String.valueOf(rid);
            resultCode = rc == null ? null : String.valueOf(rc);
            estadoJson = jsonSilencioso(r.estado());
        } catch (Exception erro) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("message", erro.getMessage());
            e.put("code", erro instanceof AppException ae ? ae.getCode() : null);
            e.put("details", erro instanceof AppException ae ? ae.getDetails() : null);
            resultado.put("sucesso", false);
            resultado.put("erro", e);
            erroJson = jsonSilencioso(e);
            if (erro instanceof AppException ae && ae.getDetails() instanceof ao.kixima.common.error.AgtRecusadoException.Details d) {
                resultCode = d.resultCode();
                if (d.respostaBruta() instanceof Map<?, ?> bruta && bruta.get("requestID") != null) requestId = String.valueOf(bruta.get("requestID"));
            }
        }

        try {
            final String fRequestId = requestId, fResultCode = resultCode, fErro = erroJson, fEstado = estadoJson;
            tx.executeWithoutResult(s -> invoiceRepository.findById(invoiceId).ifPresent(i -> {
                i.setAgtRequestId(fRequestId);
                i.setAgtResultCode(fResultCode);
                i.setAgtErro(fErro);
                i.setAgtEstado(fEstado);
            }));
        } catch (Exception erroGravar) {
            log.error("Falha ao gravar o resultado do reenvio AGT na fatura {}: {}", invoiceId, erroGravar.getMessage());
        }
        return resultado;
    }

    /** O fornecedor confirma que o valor entrou na conta — fecha o ciclo do "pagamento garantido". Uma vez por pagamento. */
    public PaymentDto confirmReceived(String paymentId, CurrentUser user, Actor actor) {
        return tx.execute(s -> {
            Payment payment = paymentRepository.findByIdComFatura(paymentId).orElseThrow(() -> new NotFoundException("Pagamento"));
            // `purchaseOrder?.supplierCompanyId ?? contract?.supplierCompanyId` — a fatura consolidada só tem contrato.
            String supplierCompanyId = payment.getInvoice().supplierCompanyId();
            if (supplierCompanyId == null || !supplierCompanyId.equals(user.companyId())) {
                throw new ForbiddenException("Só o fornecedor desta fatura pode confirmar a receção.");
            }
            if (payment.getReceivedAt() != null) throw new ConflictException("A receção deste pagamento já foi confirmada.");

            payment.confirmarRececao(Instant.now(), user.id());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("fatura", payment.getInvoice().getReference());
            detail.put("valor", ao.kixima.common.Decimais.texto(payment.getAmount()));
            detail.put("moeda", payment.getCurrency());
            auditService.record(new AuditService.Entry(actor != null ? actor : new Actor(user.id(), user.name(), null, user.companyId(), null),
                    "RECECAO_VALOR_CONFIRMADA", "Payment", payment.getId(), payment.getReference(), detail));
            return PaymentDto.de(payment, null, null);
        });
    }

    private String jsonSilencioso(Object valor) {
        if (valor == null) return null;
        try {
            return objectMapper.writeValueAsString(valor);
        } catch (Exception e) {
            return null;
        }
    }
}
