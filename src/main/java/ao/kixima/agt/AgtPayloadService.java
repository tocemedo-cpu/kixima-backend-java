package ao.kixima.agt;

import ao.kixima.common.error.AgtRecusadoException;
import ao.kixima.common.error.BusinessRuleException;
import ao.kixima.common.error.ForbiddenException;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.common.error.ServiceUnavailableException;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyRepository;
import ao.kixima.creditnote.CreditNote;
import ao.kixima.creditnote.CreditNoteRepository;
import ao.kixima.invoice.Invoice;
import ao.kixima.payment.Payment;
import ao.kixima.payment.PaymentRepository;
import ao.kixima.invoice.InvoiceLine;
import ao.kixima.invoice.InvoiceRepository;
import ao.kixima.po.PurchaseOrder;
import ao.kixima.tax.TaxService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Espelha backend/src/services/agtPayloadService.js — payload de submissão
 * AGT (e-Fatura, schema v2.0). Uma estrutura única e reutilizável no Node
 * cobre FT (fatura), NC (nota de crédito) e RC (recibo); ÂMBITO NESTE
 * MARCO (M4): só FT — o domínio CreditNote (NC) e o domínio Payment (RC)
 * ainda não foram portados para Java (ver PurchaseOrder/PoService, M3),
 * por isso {@link #construirPayload} recusa-se explicitamente a fingir
 * suporte a esses dois tipos em vez de produzir um payload incompleto.
 *
 * {@code construirPayload()} SÓ GERA E ASSINA — não submete a nada.
 * {@code submeterFatura()} é que submete mesmo o FT ao endpoint
 * registarFactura da AGT (reenvio EXPLÍCITO e VISÍVEL, ex.: ao confirmar o
 * pagamento — distinto da submissão automática e silenciosa feita no
 * aceite da PO, ver AgtSandboxSubmissionService).
 */
@Service
public class AgtPayloadService {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    // A AGT espera ISO-3166 ("PT" nas amostras); o KIXIMA guarda o país como
    // texto livre (Company.country, "Angola" por omissão). Fallback para o
    // valor tal como está — nunca inventa um código que não se sabe.
    private static final Map<String, String> PAIS_ISO = Map.of("Angola", "AO", "Portugal", "PT");

    // Placeholder sancionado pela própria AGT para comprador doméstico sem NIF
    // identificado (spec oficial, 4.1.6, linha customerTaxID) — não inventado.
    private static final String CUSTOMER_TAX_ID_DESCONHECIDO = "999999999";

    private final InvoiceRepository invoiceRepository;
    private final CompanyRepository companyRepository;
    private final TaxService taxService;
    private final AgtSigningService agtSigningService;
    private final AgtSandboxClient agtSandboxClient;
    private final AgtSeriesService agtSeriesService;
    private final String establishmentNumber;
    private final CreditNoteRepository creditNoteRepository;
    private final PaymentRepository paymentRepository;

    public AgtPayloadService(InvoiceRepository invoiceRepository, CompanyRepository companyRepository,
                              TaxService taxService, AgtSigningService agtSigningService,
                              AgtSandboxClient agtSandboxClient, AgtSeriesService agtSeriesService,
                              CreditNoteRepository creditNoteRepository, PaymentRepository paymentRepository,
                              @Value("${kixima.agt.establishment-number:}") String establishmentNumber) {
        this.creditNoteRepository = creditNoteRepository;
        this.paymentRepository = paymentRepository;
        this.invoiceRepository = invoiceRepository;
        this.companyRepository = companyRepository;
        this.taxService = taxService;
        this.agtSigningService = agtSigningService;
        this.agtSandboxClient = agtSandboxClient;
        this.agtSeriesService = agtSeriesService;
        this.establishmentNumber = establishmentNumber;
    }

    private static String paisISO(String nome) {
        if (nome == null || nome.isBlank()) return "AO";
        if (nome.matches("^[A-Z]{2}$")) return nome;
        return PAIS_ISO.getOrDefault(nome, nome);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static List<Map<String, Object>> withholdingListDe(BigDecimal valor) {
        BigDecimal v = nz(valor).setScale(2, RoundingMode.HALF_UP);
        if (v.compareTo(BigDecimal.ZERO) <= 0) return List.of();
        return List.of(AgtJson.mapa(
                "withholdingTaxType", "IRT",
                "withholdingTaxDescription", "Retenção na fonte",
                "withholdingTaxAmount", v));
    }

    private void verificarPosse(String donoId, String supplierCompanyId) {
        if (donoId == null || !donoId.equals(supplierCompanyId)) {
            throw new ForbiddenException("Este documento não pertence à empresa fornecedora indicada.");
        }
    }

    private Company carregarFornecedor(String supplierCompanyId) {
        return companyRepository.findById(supplierCompanyId).orElseThrow(() -> new NotFoundException("Empresa fornecedora"));
    }

    /** "&lt;tipo&gt; &lt;seriesCode&gt;/&lt;número&gt;" — atribuído ATOMICAMENTE a partir da série REAL da AGT (nunca a série fiscal interna). */
    private String numeroDocumento(String tipo, String docId, Instant assinadaEm) {
        int ano = assinadaEm != null ? assinadaEm.atZone(ZoneOffset.UTC).getYear() : Instant.now().atZone(ZoneOffset.UTC).getYear();
        return agtSeriesService.atribuirDocumentNo(tipo, docId, ano, establishmentNumber);
    }

    private Map<String, Object> montarDocumentoComum(String documentType, String documentNo, Instant dataDocumento,
                                                       Instant dataCriacao, String taxRegistrationNumber, Company cliente,
                                                       List<Map<String, Object>> linhas, Map<String, Object> documentTotals,
                                                       List<Map<String, Object>> withholdingTaxList) {
        return montarDocumentoComum(documentType, documentNo, dataDocumento, dataCriacao, taxRegistrationNumber, cliente,
                linhas, null, documentTotals, withholdingTaxList);
    }

    /** {@code paymentReceipt} só existe no RC (spec 4.1.6) — é ele, não `lines`, que liga o recibo à fatura que quita. */
    private Map<String, Object> montarDocumentoComum(String documentType, String documentNo, Instant dataDocumento,
                                                       Instant dataCriacao, String taxRegistrationNumber, Company cliente,
                                                       List<Map<String, Object>> linhas, Map<String, Object> paymentReceipt,
                                                       Map<String, Object> documentTotals,
                                                       List<Map<String, Object>> withholdingTaxList) {
        String documentDate = DATA.format(dataDocumento != null ? dataDocumento : (dataCriacao != null ? dataCriacao : Instant.now()));
        String systemEntryDate = (dataCriacao != null ? dataCriacao : (dataDocumento != null ? dataDocumento : Instant.now())).toString();
        String customerTaxID = cliente != null && cliente.getTaxId() != null ? cliente.getTaxId() : CUSTOMER_TAX_ID_DESCONHECIDO;
        String customerCountry = paisISO(cliente != null ? cliente.getCountry() : null);
        String companyName = cliente != null && cliente.getName() != null ? cliente.getName() : "Desconhecido";

        String jwsDocumentSignature = agtSigningService.assinarDocumento(
                documentNo, taxRegistrationNumber, documentType, documentDate, customerTaxID, customerCountry, companyName, documentTotals);

        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("documentNo", documentNo);
        m.put("documentStatus", "N");
        m.put("jwsDocumentSignature", jwsDocumentSignature);
        m.put("documentDate", documentDate);
        m.put("documentType", documentType);
        m.put("systemEntryDate", systemEntryDate);
        m.put("customerTaxID", customerTaxID);
        m.put("customerCountry", customerCountry);
        m.put("companyName", companyName);
        m.put("lines", linhas);
        if (paymentReceipt != null) m.put("paymentReceipt", paymentReceipt);
        m.put("documentTotals", documentTotals);
        if (withholdingTaxList != null && !withholdingTaxList.isEmpty()) {
            m.put("withholdingTaxList", withholdingTaxList);
        }
        return m;
    }

    /** `clienteDe`: `purchaseOrder?.buyerCompany || contract?.clientCompany` — a fatura consolidada de call-offs só tem contrato. */
    private static Company clienteDe(Invoice invoice) {
        PurchaseOrder po = invoice.getPurchaseOrder();
        if (po != null && po.getBuyerCompany() != null) return po.getBuyerCompany();
        return invoice.getContract() == null ? null : invoice.getContract().getClientCompany();
    }

    private Map<String, Object> documentoDeFatura(Invoice invoice, String fornecedorTaxId) {
        Company cliente = clienteDe(invoice);

        List<Map<String, Object>> linhas = new ArrayList<>();
        for (InvoiceLine li : invoice.getLines()) {
            linhas.add(AgtJson.mapa(
                    "lineNumber", li.getLineNumber(),
                    "productCode", li.getProductCode(),
                    "productDescription", li.getDescription(),
                    "quantity", li.getQuantity(),
                    "unitOfMeasure", "UN",
                    "unitPrice", li.getUnitPrice(),
                    "unitPriceBase", li.getUnitPrice(),
                    "debitAmount", 0,
                    "creditAmount", li.getNetAmount(),
                    "taxes", List.of(AgtJson.mapa(
                            "taxType", "IVA",
                            "taxCountryRegion", "AO",
                            "taxCode", li.getIvaTaxCode(),
                            "taxPercentage", taxService.getIvaRate().multiply(BigDecimal.valueOf(100)),
                            "taxContribution", li.getIvaAmount())),
                    "settlementAmount", 0));
        }

        String documentNo = numeroDocumento("FT", invoice.getId(), invoice.getAssinadaEm());

        Map<String, Object> documentTotals = AgtSigningService.documentTotals(
                nz(invoice.getTaxAmount()), nz(invoice.getNetAmount()), nz(invoice.getAmount()));

        return montarDocumentoComum("FT", documentNo, invoice.getAssinadaEm(), invoice.getCreatedAt(), fornecedorTaxId,
                cliente, linhas, documentTotals, withholdingListDe(invoice.getWithholdingAmount()));
    }

    /** Espelha documentoDeNotaCredito — uma linha "CORRECAO" que referencia a fatura original (retificação). */
    private Map<String, Object> documentoDeNotaCredito(CreditNote creditNote, String fornecedorTaxId) {
        Invoice invoice = creditNote.getInvoice();
        Company cliente = clienteDe(invoice);
        String faturaOriginalNo = numeroDocumento("FT", invoice.getId(), invoice.getAssinadaEm());
        BigDecimal netAmount = nz(creditNote.getNetAmount());
        BigDecimal taxAmount = nz(creditNote.getTaxAmount());

        List<Map<String, Object>> linhas = List.of(AgtJson.mapa(
                "lineNumber", 1,
                "productCode", "CORRECAO",
                "productDescription", creditNote.getMotivo(),
                "quantity", 1,
                "unitOfMeasure", "UN",
                "unitPrice", netAmount,
                "unitPriceBase", netAmount,
                "referenceInfo", AgtJson.mapa("reason", "retificacao", "reference", faturaOriginalNo, "referenceItemLineNo", 1),
                "debitAmount", netAmount,
                "creditAmount", 0,
                "taxes", List.of(AgtJson.mapa(
                        "taxType", "IVA",
                        "taxCountryRegion", "AO",
                        "taxCode", "NOR",
                        "taxPercentage", taxService.getIvaRate().multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP),
                        "taxContribution", taxAmount)),
                "settlementAmount", 0));

        // Retenção proporcional ao peso da NC sobre a fatura original — a NC não guarda a sua própria retenção.
        BigDecimal invoiceNet = nz(invoice.getNetAmount());
        BigDecimal retencao = invoiceNet.signum() > 0
                ? netAmount.divide(invoiceNet, 10, RoundingMode.HALF_UP).multiply(nz(invoice.getWithholdingAmount()))
                : BigDecimal.ZERO;

        String documentNo = numeroDocumento("NC", creditNote.getId(), creditNote.getAssinadaEm());
        Instant dataDocumento = creditNote.getAssinadaEm() != null ? creditNote.getAssinadaEm() : creditNote.getIssuedAt();
        return montarDocumentoComum("NC", documentNo, dataDocumento, creditNote.getCreatedAt(), fornecedorTaxId, cliente, linhas,
                AgtSigningService.documentTotals(taxAmount, netAmount, nz(creditNote.getAmount())), withholdingListDe(retencao));
    }

    /**
     * Espelha documentoDeRecibo — o RC quita uma fatura: sem linhas próprias; os
     * totais são os da fatura que liquida (o bruto é o efetivamente pago).
     */
    private Map<String, Object> documentoDeRecibo(Payment payment, String fornecedorTaxId) {
        Invoice invoice = payment.getInvoice();
        Company cliente = clienteDe(invoice);
        String faturaNo = numeroDocumento("FT", invoice.getId(), invoice.getAssinadaEm());
        String faturaData = DATA.format(invoice.getAssinadaEm() != null ? invoice.getAssinadaEm() : invoice.getCreatedAt());

        Map<String, Object> paymentReceipt = AgtJson.mapa("sourceDocuments", List.of(AgtJson.mapa(
                "lineNo", 1,
                "sourceDocumentID", AgtJson.mapa("originatingON", faturaNo, "documentDate", faturaData),
                "creditAmount", nz(invoice.getNetAmount()))));

        String documentNo = numeroDocumento("RC", payment.getId(), payment.getAssinadaEm());
        return montarDocumentoComum("RC", documentNo, payment.getAssinadaEm(), payment.getProcessedAt(), fornecedorTaxId, cliente,
                List.of(), paymentReceipt,
                AgtSigningService.documentTotals(nz(invoice.getTaxAmount()), nz(invoice.getNetAmount()), nz(payment.getAmount())),
                withholdingListDe(invoice.getWithholdingAmount()));
    }

    private Map<String, Object> envelope(String taxRegistrationNumber, Map<String, Object> documento) {
        return AgtJson.mapa(
                "schemaVersion", "2.0",
                "submissionUUID", UUID.randomUUID().toString(),
                "taxRegistrationNumber", taxRegistrationNumber,
                "submissionTimeStamp", Instant.now().toString(),
                "softwareInfo", agtSigningService.construirSoftwareInfo(),
                "numberOfEntries", 1,
                "documents", List.of(documento));
    }

    /**
     * Ponto de entrada — `tipo` ∈ 'FT' | 'NC' | 'RC'. Confirma que o documento
     * pertence a `supplierCompanyId` antes de assinar nada.
     */
    // NÃO readOnly: numeroDocumento()/atribuirDocumentNo() ESCREVE o número atribuído (idempotente, mas é escrita real).
    @Transactional
    public Map<String, Object> construirPayload(String tipo, String id, String supplierCompanyId) {
        agtSigningService.exigirConfiguracao();
        Company fornecedor = carregarFornecedor(supplierCompanyId);

        if ("FT".equals(tipo)) {
            Invoice invoice = invoiceRepository.findById(id).orElseThrow(() -> new NotFoundException("Fatura"));
            // partesDaFatura: o fornecedor vem da PO ou, na fatura consolidada de call-offs, do contrato.
            verificarPosse(invoice.supplierCompanyId(), supplierCompanyId);
            return envelope(fornecedor.getTaxId(), documentoDeFatura(invoice, fornecedor.getTaxId()));
        }

        if ("NC".equals(tipo)) {
            CreditNote creditNote = creditNoteRepository.findByIdComFatura(id).orElseThrow(() -> new NotFoundException("Nota de crédito"));
            verificarPosse(creditNote.getInvoice().supplierCompanyId(), supplierCompanyId);
            return envelope(fornecedor.getTaxId(), documentoDeNotaCredito(creditNote, fornecedor.getTaxId()));
        }

        if ("RC".equals(tipo)) {
            Payment payment = paymentRepository.findByIdComFatura(id).orElseThrow(() -> new NotFoundException("Recibo"));
            verificarPosse(payment.getInvoice().supplierCompanyId(), supplierCompanyId);
            return envelope(fornecedor.getTaxId(), documentoDeRecibo(payment, fornecedor.getTaxId()));
        }

        throw new BusinessRuleException(
                "Tipo de documento \"" + tipo + "\" não suportado. Use FT, NC ou RC — FR não tem produtor automático no "
                        + "KIXIMA: a fatura e o recibo são sempre documentos separados no modelo de pagamento garantido.");
    }

    private Map<String, Object> construirPedidoEstado(String taxRegistrationNumber, String requestID) {
        return AgtJson.mapa(
                "schemaVersion", "2.0",
                "submissionUUID", UUID.randomUUID().toString(),
                "taxRegistrationNumber", taxRegistrationNumber,
                "submissionTimeStamp", Instant.now().toString(),
                "softwareInfo", agtSigningService.construirSoftwareInfo(),
                "requestID", requestID);
    }

    public record EstadoConsultado(Map<String, Object> pedido, Map<String, Object> resposta, String erro) {
    }

    /**
     * NUNCA lança: já se sabe que a AGT deu um requestID a este pedido, uma
     * falha a consultar o estado não pode desfazer isso nem esconder o
     * resultado principal de registarFactura.
     */
    private EstadoConsultado consultarEstadoSePossivel(String taxRegistrationNumber, String requestID) {
        if (requestID == null) return null;
        Map<String, Object> pedido = construirPedidoEstado(taxRegistrationNumber, requestID);
        try {
            Map<String, Object> resposta = agtSandboxClient.obterEstado(pedido);
            return new EstadoConsultado(pedido, resposta, null);
        } catch (Exception erroEstado) {
            return new EstadoConsultado(pedido, null, erroEstado.getMessage());
        }
    }

    public record ResultadoSubmissao(Map<String, Object> payload, Map<String, Object> resposta, EstadoConsultado estado) {
    }

    /**
     * Constrói o payload e SUBMETE-O ao endpoint registarFactura da AGT.
     * Lança AgtRecusadoException (502) se a AGT recusar.
     */
    private ResultadoSubmissao submeterDocumento(String tipo, String id, String supplierCompanyId) {
        Map<String, Object> payload = construirPayload(tipo, id, supplierCompanyId);
        String taxRegistrationNumber = (String) payload.get("taxRegistrationNumber");
        try {
            Map<String, Object> resposta = agtSandboxClient.registarFactura(payload);
            Object requestId = resposta == null ? null : resposta.get("requestID");
            EstadoConsultado estado = consultarEstadoSePossivel(taxRegistrationNumber, requestId == null ? null : String.valueOf(requestId));
            return new ResultadoSubmissao(payload, resposta, estado);
        } catch (AgtApiException erro) {
            Object requestId = erro.getRespostaBruta() instanceof Map<?, ?> m ? m.get("requestID") : null;
            EstadoConsultado estado = consultarEstadoSePossivel(taxRegistrationNumber, requestId == null ? null : String.valueOf(requestId));
            AgtRecusadoException recusado = new AgtRecusadoException(erro.getMessage(), erro.getEndpoint(), erro.getResultCode(),
                    erro.getErrorList(), erro.getRespostaBruta(), payload);
            throw recusado;
        }
    }

    /** Reenvio explícito e visível do FT — ver PoService/paymentService (M3d). */
    @Transactional
    public ResultadoSubmissao submeterFatura(String invoiceId, String supplierCompanyId) {
        return submeterDocumento("FT", invoiceId, supplierCompanyId);
    }

    /** Submissão explícita e visível da NC — ver CreditNoteService.anular. */
    @Transactional
    public ResultadoSubmissao submeterNotaCredito(String creditNoteId, String supplierCompanyId) {
        return submeterDocumento("NC", creditNoteId, supplierCompanyId);
    }

    /**
     * Estado REAL do processamento da ÚLTIMA submissão do FT desta fatura
     * (obterEstado) — usa o `requestID` já gravado (Invoice.agtRequestId).
     */
    @Transactional(readOnly = true)
    public EstadoConsultado consultarEstadoFatura(String invoiceId, String supplierCompanyId) {
        Company fornecedor = carregarFornecedor(supplierCompanyId);
        Invoice invoice = invoiceRepository.findById(invoiceId).orElseThrow(() -> new NotFoundException("Fatura"));
        verificarPosse(invoice.supplierCompanyId(), supplierCompanyId);

        if (invoice.getAgtRequestId() == null) {
            throw new BusinessRuleException(
                    "A fatura \"" + invoice.getReference() + "\" ainda não tem nenhum requestID da AGT gravado — só é "
                            + "possível consultar o estado depois de pelo menos uma submissão que tenha chegado à AGT.");
        }

        Map<String, Object> pedido = construirPedidoEstado(fornecedor.getTaxId(), invoice.getAgtRequestId());
        try {
            Map<String, Object> resposta = agtSandboxClient.obterEstado(pedido);
            return new EstadoConsultado(pedido, resposta, null);
        } catch (AgtApiException erro) {
            throw new AgtRecusadoException(erro.getMessage(), erro.getEndpoint(), erro.getResultCode(),
                    erro.getErrorList(), erro.getRespostaBruta(), pedido);
        }
    }
}
