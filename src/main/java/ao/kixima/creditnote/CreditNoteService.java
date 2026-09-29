package ao.kixima.creditnote;

import ao.kixima.agt.AgtPayloadService;
import ao.kixima.agt.AgtSandboxSubmissionService;
import ao.kixima.audit.Actor;
import ao.kixima.audit.AuditService;
import ao.kixima.common.error.AppException;
import ao.kixima.common.error.ConflictException;
import ao.kixima.common.error.ForbiddenException;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.common.error.ValidationException;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyRepository;
import ao.kixima.creditnote.dto.AnulacaoDto;
import ao.kixima.creditnote.dto.CreditNoteDto;
import ao.kixima.faturacao.FaturacaoService;
import ao.kixima.invoice.Invoice;
import ao.kixima.invoice.InvoiceRepository;
import ao.kixima.common.reference.ReferenceCounterService;
import ao.kixima.notification.NotificationChannel;
import ao.kixima.notification.NotificationService;
import ao.kixima.notification.NotificationType;
import ao.kixima.po.PurchaseOrder;
import ao.kixima.security.CurrentUser;
import ao.kixima.security.PersonaRole;
import ao.kixima.tax.TaxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Espelha backend/src/services/creditNoteService.js — a correção fiscal de
 * uma fatura já emitida. O crédito nunca é arbitrário: duas notas de
 * crédito parciais não podem, juntas, exceder o valor da fatura (a fatura é
 * bloqueada — FOR UPDATE — enquanto se recalcula o saldo).
 *
 * Transações: a nota nasce numa transação própria (lock + saldo +
 * certificação + auditoria); notificação e submissão à AGT correm DEPOIS,
 * tal como no Node depois do {@code prisma.$transaction} fechar.
 *
 * NÃO PORTADO: o ramo {@code contract} (faturas consolidadas de call-offs) —
 * toda a fatura em Java é de uma PO.
 */
@Service
public class CreditNoteService {

    private static final Logger log = LoggerFactory.getLogger(CreditNoteService.class);

    /** As partes de uma fatura, já resolvidas dentro de uma transação (as relações são lazy). */
    public record Fatura(Invoice invoice, String reference, BigDecimal amount, String currency, String supplierCompanyId,
                         String buyerCompanyId) {
    }

    private final CreditNoteRepository creditNoteRepository;
    private final InvoiceRepository invoiceRepository;
    private final CompanyRepository companyRepository;
    private final FaturacaoService faturacaoService;
    private final TaxService taxService;
    private final ReferenceCounterService referenceCounterService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final AgtSandboxSubmissionService agtSandboxSubmissionService;
    private final AgtPayloadService agtPayloadService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public CreditNoteService(CreditNoteRepository creditNoteRepository, InvoiceRepository invoiceRepository,
                              CompanyRepository companyRepository, FaturacaoService faturacaoService, TaxService taxService,
                              ReferenceCounterService referenceCounterService, AuditService auditService,
                              NotificationService notificationService, AgtSandboxSubmissionService agtSandboxSubmissionService,
                              AgtPayloadService agtPayloadService, ObjectMapper objectMapper,
                              PlatformTransactionManager transactionManager) {
        this.creditNoteRepository = creditNoteRepository;
        this.invoiceRepository = invoiceRepository;
        this.companyRepository = companyRepository;
        this.faturacaoService = faturacaoService;
        this.taxService = taxService;
        this.referenceCounterService = referenceCounterService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.agtSandboxSubmissionService = agtSandboxSubmissionService;
        this.agtPayloadService = agtPayloadService;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    private static BigDecimal round2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    public Fatura carregarFatura(String invoiceId) {
        return tx.execute(status -> {
            Invoice invoice = invoiceRepository.findById(invoiceId).orElseThrow(() -> new NotFoundException("Fatura"));
            PurchaseOrder po = invoice.getPurchaseOrder();
            return new Fatura(invoice, invoice.getReference(), invoice.getAmount(), invoice.getCurrency(),
                    po == null ? null : po.getSupplierCompanyId(), po == null ? null : po.getBuyerCompanyId());
        });
    }

    public BigDecimal totalCreditado(String invoiceId) {
        BigDecimal soma = creditNoteRepository.totalCreditado(invoiceId);
        return round2(soma == null ? BigDecimal.ZERO : soma);
    }

    /** Só o FORNECEDOR desta fatura (o emitente fiscal) ou o ADMIN_SISTEMA (área FATURACAO, já verificada na rota) a corrigem. */
    public CreditNote emitir(String invoiceId, String motivo, BigDecimal amount, CurrentUser user, Actor actor) {
        if (motivo == null || motivo.isBlank()) {
            throw new ValidationException("Indique o motivo da nota de crédito — uma correção fiscal sem motivo não se consegue explicar depois.");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new ValidationException("O valor da nota de crédito tem de ser um número positivo.");
        }
        BigDecimal valor = round2(amount);
        String motivoLimpo = motivo.strip();

        Fatura fatura = carregarFatura(invoiceId);
        if (user.role() != PersonaRole.ADMIN_SISTEMA && (fatura.supplierCompanyId() == null || !fatura.supplierCompanyId().equals(user.companyId()))) {
            throw new ForbiddenException("Só o fornecedor desta fatura pode pedir uma nota de crédito.");
        }

        // Net/imposto derivados proporcionalmente pela MESMA taxa fixa que gerou a fatura.
        BigDecimal netAmount = round2(valor.divide(BigDecimal.ONE.add(taxService.getIvaRate()), 10, RoundingMode.HALF_UP));
        BigDecimal taxAmount = round2(valor.subtract(netAmount));

        String reference = referenceCounterService.nextReference("NC", "creditNote");
        Company supplier = fatura.supplierCompanyId() == null ? null : companyRepository.findById(fatura.supplierCompanyId()).orElse(null);

        CreditNote nota = tx.execute(status -> {
            // Bloqueia a fatura até esta transação terminar — duas notas pedidas em paralelo
            // recalculam o saldo em série, já com a primeira contabilizada.
            invoiceRepository.findByIdParaAtualizar(invoiceId).orElseThrow(() -> new NotFoundException("Fatura"));

            BigDecimal jaCreditado = totalCreditado(invoiceId);
            BigDecimal porCreditar = round2(fatura.amount().subtract(jaCreditado));
            if (valor.compareTo(porCreditar) > 0) {
                throw new ConflictException("Esta fatura já tem " + jaCreditado.stripTrailingZeros().toPlainString() + " " + fatura.currency()
                        + " creditados. Só pode creditar até " + porCreditar.stripTrailingZeros().toPlainString() + " " + fatura.currency()
                        + " — o que pediu excede o saldo da fatura.");
            }

            FaturacaoService.Certificacao certificacao = faturacaoService.atribuir(Instant.now(), valor,
                    faturacaoService.serieNotaCreditoDoFornecedor(supplier == null ? null : supplier.getSerieFiscal()),
                    supplier == null ? null : supplier.getDataAdesaoFacturacaoElectronica());

            CreditNote criada = new CreditNote(UUID.randomUUID().toString(), reference, invoiceId, motivoLimpo, valor, netAmount,
                    taxAmount, fatura.currency(), user.id(), Instant.now(), certificacao);
            creditNoteRepository.save(criada);

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("fatura", fatura.reference());
            detail.put("motivo", motivoLimpo);
            detail.put("valor", ao.kixima.common.Decimais.texto(valor));
            detail.put("moeda", fatura.currency());
            auditService.record(new AuditService.Entry(actor != null ? actor : new Actor(user.id(), user.name(), null, user.companyId(), null),
                    "NOTA_CREDITO_EMITIDA", "CreditNote", criada.getId(), criada.getReference(), detail));
            return criada;
        });

        if (fatura.buyerCompanyId() != null) {
            notificationService.notifyUsersByRole(fatura.buyerCompanyId(), List.of(PersonaRole.FINANCEIRO, PersonaRole.COMPANY_ADMIN),
                    NotificationType.NOTA_CREDITO_EMITIDA, "Nota de crédito emitida",
                    "Nota de crédito " + nota.getReference() + " de " + nota.getAmount().stripTrailingZeros().toPlainString() + " "
                            + nota.getCurrency() + ", referente à fatura " + fatura.reference() + ". Motivo: " + nota.getMotivo(),
                    NotificationChannel.IN_APP_EMAIL, "Invoice", invoiceId);
        }

        if (fatura.supplierCompanyId() != null) {
            agtSandboxSubmissionService.submeter("NC", nota.getId(), fatura.supplierCompanyId());
        }
        return nota;
    }

    /**
     * Anula a fatura por completo — nota de crédito automática pelo valor TOTAL
     * ainda por creditar, e a submissão dessa NC à AGT (registarFactura) fica
     * VISÍVEL na resposta e gravada na própria NC. NÃO define Invoice.status =
     * CANCELADA: "fatura anulada" calcula-se a partir do saldo creditado.
     */
    public AnulacaoDto anular(String invoiceId, String motivo, CurrentUser user, Actor actor) {
        Fatura fatura = carregarFatura(invoiceId);
        BigDecimal porCreditar = round2(fatura.amount().subtract(totalCreditado(invoiceId)));
        if (porCreditar.signum() <= 0) {
            throw new ConflictException("A fatura \"" + fatura.reference() + "\" já está totalmente creditada — não há saldo por anular.");
        }

        String motivoFinal = motivo != null && !motivo.isBlank() ? motivo.strip() : "Anulação da fatura " + fatura.reference();
        CreditNote nota = emitir(invoiceId, motivoFinal, porCreditar, user, actor);

        Map<String, Object> agtSubmission = null;
        if (fatura.supplierCompanyId() != null) {
            agtSubmission = new LinkedHashMap<>();
            String requestId = null;
            String resultCode = null;
            String erroJson = null;
            String estadoJson = null;
            try {
                AgtPayloadService.ResultadoSubmissao r = agtPayloadService.submeterNotaCredito(nota.getId(), fatura.supplierCompanyId());
                agtSubmission.put("sucesso", true);
                agtSubmission.put("payload", r.payload());
                agtSubmission.put("resposta", r.resposta());
                agtSubmission.put("estado", r.estado());
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
                agtSubmission.put("sucesso", false);
                agtSubmission.put("erro", e);
                erroJson = jsonSilencioso(e);
                if (erro instanceof AppException ae && ae.getDetails() instanceof ao.kixima.common.error.AgtRecusadoException.Details d) {
                    resultCode = d.resultCode();
                    if (d.respostaBruta() instanceof Map<?, ?> bruta && bruta.get("requestID") != null) {
                        requestId = String.valueOf(bruta.get("requestID"));
                    }
                }
            }

            // Grava o resultado na própria NC — numa falha de escrita, só regista em log: a NC já existe e já foi submetida.
            try {
                final String fRequestId = requestId, fResultCode = resultCode, fErro = erroJson, fEstado = estadoJson;
                tx.executeWithoutResult(status -> creditNoteRepository.findById(nota.getId())
                        .ifPresent(nc -> nc.registarSubmissaoAgt(fRequestId, fResultCode, fErro, fEstado)));
            } catch (Exception erroGravar) {
                log.error("Falha ao gravar o resultado da submissão AGT na nota de crédito de anulação {}: {}", nota.getId(), erroGravar.getMessage());
            }
        }

        CreditNote atualizada = creditNoteRepository.findById(nota.getId()).orElse(nota);
        return new AnulacaoDto(CreditNoteDto.de(atualizada, objectMapper), agtSubmission);
    }

    /** Vê as notas de crédito de uma fatura quem é parte nela (comprador ou fornecedor) ou o ADMIN_SISTEMA. */
    public List<CreditNoteDto> listar(String invoiceId, CurrentUser user) {
        Fatura fatura = carregarFatura(invoiceId);
        if (user.role() != PersonaRole.ADMIN_SISTEMA) {
            boolean parte = user.companyId() != null
                    && (user.companyId().equals(fatura.supplierCompanyId()) || user.companyId().equals(fatura.buyerCompanyId()));
            if (!parte) throw new ForbiddenException("Só as partes desta fatura podem ver as suas notas de crédito.");
        }
        return creditNoteRepository.findByInvoiceIdOrderByIssuedAtAsc(invoiceId).stream().map(c -> CreditNoteDto.de(c, objectMapper)).toList();
    }

    public CreditNoteDto toDto(CreditNote nota) {
        return CreditNoteDto.de(nota, objectMapper);
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
