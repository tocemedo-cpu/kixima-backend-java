package ao.kixima.conciliacao;

import ao.kixima.agt.AgtSandboxSubmissionService;
import ao.kixima.audit.Actor;
import ao.kixima.audit.AuditService;
import ao.kixima.common.error.BusinessRuleException;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.common.pagination.PaginaResposta;
import ao.kixima.common.pagination.Paginacao;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyRepository;
import ao.kixima.conciliacao.dto.LinhaExtratoDto;
import ao.kixima.conciliacao.dto.LinhaExtratoInput;
import ao.kixima.faturacao.FaturacaoService;
import ao.kixima.invoice.Invoice;
import ao.kixima.invoice.InvoiceRepository;
import ao.kixima.invoice.InvoiceStatus;
import ao.kixima.payment.Payment;
import ao.kixima.payment.PaymentRepository;
import ao.kixima.po.PoStatus;
import ao.kixima.po.PurchaseOrder;
import ao.kixima.security.CurrentUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Espelha backend/src/services/conciliacaoService.js — referência bancária e
 * conciliação automática do extrato. A REGRA QUE GOVERNA TUDO AQUI: na
 * dúvida, NÃO se dá por paga. Três condições (referência, moeda, valor ao
 * cêntimo) e as três têm de bater; o que não casa fica a aguardar decisão
 * humana — o estado em que tudo estava antes.
 *
 * NÃO PORTADO: o ramo {@code contract} das faturas consolidadas (call-offs).
 */
@Service
public class ConciliacaoService {

    public static final String POR_CONCILIAR = "POR_CONCILIAR";
    public static final String CONCILIADA = "CONCILIADA";
    public static final String SEM_CORRESPONDENCIA = "SEM_CORRESPONDENCIA";
    public static final String DIVERGENTE = "DIVERGENTE";

    /** Sem O, I, 0 e 1 — os quatro que se trocam entre si numa transferência escrita à mão. */
    private static final String ALFABETO = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Pattern REFERENCIA = Pattern.compile("KX([ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8})");
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Desfecho(String estado, String motivo) {
    }

    public record ResultadoImportacao(int importadas, int repetidas, int conciliadas, int porResolver, List<Map<String, Object>> detalhes) {
    }

    private final InvoiceRepository invoiceRepository;
    private final LinhaExtratoRepository linhaExtratoRepository;
    private final PaymentRepository paymentRepository;
    private final CompanyRepository companyRepository;
    private final FaturacaoService faturacaoService;
    private final AuditService auditService;
    private final AgtSandboxSubmissionService agtSandboxSubmissionService;
    private final TransactionTemplate tx;

    public ConciliacaoService(InvoiceRepository invoiceRepository, LinhaExtratoRepository linhaExtratoRepository,
                               PaymentRepository paymentRepository, CompanyRepository companyRepository,
                               FaturacaoService faturacaoService, AuditService auditService,
                               AgtSandboxSubmissionService agtSandboxSubmissionService,
                               PlatformTransactionManager transactionManager) {
        this.invoiceRepository = invoiceRepository;
        this.linhaExtratoRepository = linhaExtratoRepository;
        this.paymentRepository = paymentRepository;
        this.companyRepository = companyRepository;
        this.faturacaoService = faturacaoService;
        this.auditService = auditService;
        this.agtSandboxSubmissionService = agtSandboxSubmissionService;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // --- Referência bancária ---------------------------------------------------

    static String gerarReferencia() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 10; i++) s.append(ALFABETO.charAt(RANDOM.nextInt(ALFABETO.length())));
        return "KX" + s.substring(0, 4) + "-" + s.substring(4, 8);
    }

    /** Atribui e persiste uma referência única na fatura já criada (gerida pela transacção do chamador); repete em caso de colisão. */
    public String atribuirReferencia(Invoice invoice) {
        for (int tentativa = 0; tentativa < 5; tentativa++) {
            String referencia = gerarReferencia();
            if (invoiceRepository.findByReferenciaPagamento(referencia).isPresent()) continue;
            invoice.setReferenciaPagamento(referencia);
            return referencia;
        }
        throw new IllegalStateException("Não foi possível gerar uma referência de pagamento única após 5 tentativas.");
    }

    /** Extrai a referência da descrição como as pessoas a escrevem — espaços, minúsculas, sem hífen, no meio de outro texto. */
    public static String extrairReferencia(String descricao) {
        String limpo = (descricao == null ? "" : descricao).toUpperCase().replaceAll("[^A-Z0-9]", "");
        Matcher m = REFERENCIA.matcher(limpo);
        return m.find() ? "KX" + m.group(1).substring(0, 4) + "-" + m.group(1).substring(4, 8) : null;
    }

    // --- Importação ------------------------------------------------------------

    private static Instant dataValor(String bruta) {
        if (bruta == null || bruta.isBlank()) throw new BusinessRuleException("Cada linha do extrato precisa da data-valor.");
        try {
            return Instant.parse(bruta);
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(bruta).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException e2) {
                throw new BusinessRuleException("Data-valor inválida na linha do extrato: \"" + bruta + "\".");
            }
        }
    }

    /** Idempotente por `idNoBanco`: reenviar o mesmo extrato é banal e não pode pagar a mesma fatura duas vezes. */
    public ResultadoImportacao importarExtrato(List<LinhaExtratoInput> linhas, CurrentUser actor) {
        int importadas = 0, repetidas = 0, conciliadas = 0, porResolver = 0;
        List<Map<String, Object>> detalhes = new ArrayList<>();

        for (LinhaExtratoInput bruta : linhas) {
            String idNoBanco = bruta.idNoBanco() == null ? "" : bruta.idNoBanco().trim();
            if (idNoBanco.isEmpty()) throw new BusinessRuleException("Cada linha do extrato precisa de um identificador do banco.");

            if (linhaExtratoRepository.findByIdNoBanco(idNoBanco).isPresent()) {
                repetidas++;
                continue;
            }

            BigDecimal montante = bruta.montante() == null ? BigDecimal.ZERO : bruta.montante();
            String referencia = bruta.referencia() != null && !bruta.referencia().isBlank() ? bruta.referencia() : extrairReferencia(bruta.descricao());
            // Só entram entradas de dinheiro: um débito com referência não é um pagamento.
            boolean credito = montante.signum() > 0;

            LinhaExtrato criada = tx.execute(s -> linhaExtratoRepository.save(new LinhaExtrato(UUID.randomUUID().toString(), idNoBanco,
                    dataValor(bruta.dataValor()), montante, bruta.moeda() == null || bruta.moeda().isBlank() ? "AOA" : bruta.moeda(),
                    bruta.descricao(), referencia, Instant.now())));
            importadas++;

            Desfecho desfecho = credito
                    ? tentarConciliar(criada.getId(), actor)
                    : marcar(criada.getId(), SEM_CORRESPONDENCIA, "Linha a débito — não é uma entrada de dinheiro.", null);

            if (CONCILIADA.equals(desfecho.estado())) conciliadas++;
            else porResolver++;
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("idNoBanco", idNoBanco);
            d.put("estado", desfecho.estado());
            d.put("motivo", desfecho.motivo());
            detalhes.add(d);
        }
        return new ResultadoImportacao(importadas, repetidas, conciliadas, porResolver, detalhes);
    }

    private Desfecho marcar(String linhaId, String estado, String motivo, String invoiceId) {
        tx.executeWithoutResult(s -> linhaExtratoRepository.findById(linhaId).ifPresent(l -> l.marcar(estado, motivo, invoiceId)));
        return new Desfecho(estado, motivo);
    }

    private record FaturaCandidata(String id, String reference, BigDecimal amount, String currency, String supplierCompanyId,
                                   boolean jaPaga) {
    }

    /** Tenta casar UMA linha com UMA fatura — três condições, e as três têm de bater. */
    public Desfecho tentarConciliar(String linhaId, CurrentUser actor) {
        LinhaExtrato linha = linhaExtratoRepository.findById(linhaId).orElseThrow(() -> new NotFoundException("Linha do extrato"));
        if (linha.getReferencia() == null) {
            return marcar(linhaId, SEM_CORRESPONDENCIA, "Sem referência reconhecível na descrição.", null);
        }

        FaturaCandidata fatura = tx.execute(s -> invoiceRepository.findByReferenciaPagamento(linha.getReferencia()).map(i -> {
            PurchaseOrder po = i.getPurchaseOrder();
            return new FaturaCandidata(i.getId(), i.getReference(), i.getAmount(), i.getCurrency(),
                    po == null ? null : po.getSupplierCompanyId(), paymentRepository.findByInvoiceId(i.getId()).isPresent());
        }).orElse(null));

        if (fatura == null) {
            return marcar(linhaId, SEM_CORRESPONDENCIA, "Nenhuma fatura com a referência " + linha.getReferencia() + ".", null);
        }
        if (fatura.jaPaga()) {
            // O mesmo pagamento a chegar duas vezes, ou um a mais — sinalizado para alguém devolver, nunca silenciado.
            return marcar(linhaId, DIVERGENTE, "A fatura já tem pagamento registado.", fatura.id());
        }
        if (!linha.getMoeda().equals(fatura.currency())) {
            return marcar(linhaId, DIVERGENTE, "Moeda diferente: extrato em " + linha.getMoeda() + ", fatura em " + fatura.currency() + ".", fatura.id());
        }
        // Comparação ao cêntimo — uma tolerância "pequena" seria uma decisão de negócio disfarçada de detalhe técnico.
        String esperado = fatura.amount().setScale(2, RoundingMode.HALF_UP).toPlainString();
        String recebido = linha.getMontante().setScale(2, RoundingMode.HALF_UP).toPlainString();
        if (!esperado.equals(recebido)) {
            return marcar(linhaId, DIVERGENTE, "Valor diferente: esperado " + esperado + " " + fatura.currency() + ", recebido " + recebido + ".", fatura.id());
        }

        Company supplier = fatura.supplierCompanyId() == null ? null : companyRepository.findById(fatura.supplierCompanyId()).orElse(null);

        // Tudo bate: paga-se, e as escritas vivem na mesma transação.
        Payment pagamento;
        try {
            pagamento = tx.execute(s -> {
                Invoice invoice = invoiceRepository.findById(fatura.id()).orElseThrow();
                FaturacaoService.Certificacao certificacao = faturacaoService.atribuir(linha.getDataValor(), invoice.getAmount(),
                        faturacaoService.serieReciboDoFornecedor(supplier == null ? null : supplier.getSerieFiscal()),
                        supplier == null ? null : supplier.getDataAdesaoFacturacaoElectronica());
                // A conciliação não tem uma pessoa por trás: a linha do extrato é a origem, não se atribui a quem não decidiu.
                Payment p = Payment.conciliado(UUID.randomUUID().toString(), invoice.getId(), invoice.getAmount(), invoice.getCurrency(),
                        actor == null ? null : actor.id(), "CONC-" + linha.getIdNoBanco(), linha.getDataValor(), certificacao);
                paymentRepository.saveAndFlush(p);
                invoice.setStatus(InvoiceStatus.PAGA);
                PurchaseOrder po = invoice.getPurchaseOrder();
                if (po != null) {
                    po.setStatus(PoStatus.PAGA);
                    po.setPaidAt(linha.getDataValor());
                }
                return p;
            });
        } catch (DataIntegrityViolationException duplicado) {
            // Payment.invoiceId é único — duas linhas de extrato distintas para a mesma fatura: a segunda não é falha do lote.
            return marcar(linhaId, DIVERGENTE, "A fatura já tem pagamento registado por outra linha do extrato.", fatura.id());
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("linhaExtrato", linha.getIdNoBanco());
        detail.put("referencia", linha.getReferencia());
        detail.put("montante", recebido);
        auditService.recordSafe(new AuditService.Entry(
                new Actor(actor == null ? null : actor.id(), actor == null ? "Conciliação automática" : actor.name(), null, null, null),
                "PAGAMENTO_CONCILIADO", "Invoice", fatura.id(), fatura.reference(), detail));

        if (fatura.supplierCompanyId() != null) {
            agtSandboxSubmissionService.submeter("RC", pagamento.getId(), fatura.supplierCompanyId());
        }
        return marcar(linhaId, CONCILIADA, null, fatura.id());
    }

    /** As linhas que ficaram por resolver — se esta lista só crescer, o formato do banco mudou e ninguém deu por isso. */
    public PaginaResposta<LinhaExtratoDto> porResolver(Integer page, Integer limit) {
        return tx.execute(s -> Paginacao.envelope(linhaExtratoRepository
                .findPorResolver(List.of(SEM_CORRESPONDENCIA, DIVERGENTE), Paginacao.parametros(page, limit))
                .map(LinhaExtratoDto::de)));
    }

    /** Reprocessa uma linha depois de alguém corrigir a referência à mão. */
    public Desfecho reconciliarManualmente(String linhaId, String referencia, CurrentUser actor) {
        tx.executeWithoutResult(s -> {
            LinhaExtrato linha = linhaExtratoRepository.findById(linhaId).orElseThrow(() -> new NotFoundException("Linha do extrato"));
            if (CONCILIADA.equals(linha.getEstado())) throw new BusinessRuleException("Esta linha já foi conciliada.");
            if (referencia != null && !referencia.isBlank()) linha.setReferencia(referencia);
        });
        return tentarConciliar(linhaId, actor);
    }
}
