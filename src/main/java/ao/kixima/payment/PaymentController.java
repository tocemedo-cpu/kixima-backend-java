package ao.kixima.payment;

import ao.kixima.audit.Actor;
import ao.kixima.audit.AuditService;
import ao.kixima.creditnote.CreditNoteService;
import ao.kixima.creditnote.dto.AnulacaoDto;
import ao.kixima.creditnote.dto.CreditNoteDto;
import ao.kixima.creditnote.dto.CreditNoteRequest;
import ao.kixima.invoice.dto.InvoiceDto;
import ao.kixima.payment.dto.PaymentDto;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.RequirePermission;
import ao.kixima.security.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static ao.kixima.security.AdminArea.FATURACAO;
import static ao.kixima.security.PersonaRole.ADMIN_SISTEMA;
import static ao.kixima.security.PersonaRole.COMPANY_ADMIN;
import static ao.kixima.security.PersonaRole.COMPRADOR;
import static ao.kixima.security.PersonaRole.FINANCEIRO;
import static ao.kixima.security.PersonaRole.FORNECEDOR;
import static org.springframework.http.HttpStatus.CREATED;

/** Espelha paymentRoutes.js/paymentController.js — pagamento com comprovativo, receção do valor, notas de crédito, anulação. */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final CreditNoteService creditNoteService;
    private final AuditService auditService;

    public PaymentController(PaymentService paymentService, CreditNoteService creditNoteService, AuditService auditService) {
        this.paymentService = paymentService;
        this.creditNoteService = creditNoteService;
        this.auditService = auditService;
    }

    private Actor actor(HttpServletRequest req) {
        return auditService.actorFrom(CurrentUserHolder.get(), req);
    }

    /** Confirmação de receção do valor — lado do FORNECEDOR. */
    @PatchMapping("/{paymentId}/confirm-received")
    @RequireRole({FORNECEDOR, COMPANY_ADMIN, FINANCEIRO})
    public PaymentDto confirmReceived(@PathVariable String paymentId, HttpServletRequest req) {
        return paymentService.confirmReceived(paymentId, CurrentUserHolder.get(), actor(req));
    }

    /** O fornecedor desta fatura corrige-a, ou o Admin do Sistema com a área FATURACAO — a posse real confirma-se no serviço. */
    @PostMapping("/invoices/{invoiceId}/notas-credito")
    @ResponseStatus(CREATED)
    @RequireRole({FORNECEDOR, COMPANY_ADMIN, ADMIN_SISTEMA})
    @RequirePermission(FATURACAO)
    public CreditNoteDto emitirNotaCredito(@PathVariable String invoiceId, @RequestBody(required = false) CreditNoteRequest body,
                                           HttpServletRequest req) {
        var nota = creditNoteService.emitir(invoiceId, body == null ? null : body.motivo(), body == null ? null : body.amount(),
                CurrentUserHolder.get(), actor(req));
        return creditNoteService.toDto(nota);
    }

    @GetMapping("/invoices/{invoiceId}/notas-credito")
    @RequireRole({FORNECEDOR, COMPANY_ADMIN, FINANCEIRO, COMPRADOR, ADMIN_SISTEMA})
    @RequirePermission(FATURACAO)
    public List<CreditNoteDto> listarNotasCredito(@PathVariable String invoiceId) {
        return creditNoteService.listar(invoiceId, CurrentUserHolder.get());
    }

    /** Anular fatura — nota de crédito de valor total, mesma posse/permissão da nota de crédito. */
    @PostMapping("/invoices/{invoiceId}/anular")
    @ResponseStatus(CREATED)
    @RequireRole({FORNECEDOR, COMPANY_ADMIN, ADMIN_SISTEMA})
    @RequirePermission(FATURACAO)
    public AnulacaoDto anularFatura(@PathVariable String invoiceId, @RequestBody(required = false) CreditNoteRequest body,
                                    HttpServletRequest req) {
        return creditNoteService.anular(invoiceId, body == null ? null : body.motivo(), CurrentUserHolder.get(), actor(req));
    }

    @GetMapping("/invoices/pending")
    @RequireRole({FINANCEIRO, COMPANY_ADMIN})
    public List<InvoiceDto> pendingInvoices() {
        return paymentService.listPendingInvoices(CurrentUserHolder.get().companyId());
    }

    @GetMapping("/history")
    @RequireRole({FINANCEIRO, COMPANY_ADMIN})
    public List<PaymentDto> history() {
        return paymentService.listPaymentHistory(CurrentUserHolder.get().companyId());
    }

    /** Pagamento com comprovativo OBRIGATÓRIO (multipart, campo "proof": PDF/imagem). */
    @PostMapping("/invoices/{invoiceId}/pay")
    @ResponseStatus(CREATED)
    @RequireRole({FINANCEIRO, COMPANY_ADMIN})
    public PaymentDto pay(@PathVariable String invoiceId, @RequestParam(required = false) MultipartFile proof, HttpServletRequest req) {
        return paymentService.processPayment(invoiceId, CurrentUserHolder.get(), proof, actor(req));
    }
}
