package ao.kixima.payment;

import ao.kixima.common.error.NotFoundException;
import ao.kixima.common.money.FxService;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyRepository;
import ao.kixima.invoice.Invoice;
import ao.kixima.invoice.InvoiceRepository;
import ao.kixima.payment.dto.PlatformFeeBookDto;
import ao.kixima.payment.dto.PlatformFeeDto;
import ao.kixima.payment.dto.PlatformFeeStatementDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Espelha backend/src/services/platformFeeService.js — a taxa da plataforma
 * (comissão KIXIMA), à parte da PO/Fatura, cobrada ao fornecedor:
 * <ul>
 *   <li>ATÉ ao limiar (11.500 USD por transação): 8 USD por PO + 15 USD por fatura;</li>
 *   <li>ACIMA: 0,20 % do valor da transação, uma só vez, e essa percentagem
 *   já INCLUI a parcela da PO e a da fatura.</li>
 * </ul>
 * As taxas são definidas e cobradas em USD; as POs continuam em Kwanzas —
 * o câmbio configurável ({@link FxService}) só serve para aferir o limiar.
 *
 * Inclui o extrato por empresa ({@link #statementFor}) e o livro de taxas do
 * Admin ({@link #listPlatformFees}/{@link #chargePlatformFee}, de adminService.js).
 */
@Service
public class PlatformFeeService {

    public static final String CURRENCY = "USD";

    public record Calculo(int poCount, BigDecimal perPo, BigDecimal perInvoice, BigDecimal amount, String currency,
                          String basis, BigDecimal poValueUsd) {
    }

    private final PlatformFeeRepository platformFeeRepository;
    private final CompanyRepository companyRepository;
    private final InvoiceRepository invoiceRepository;
    private final FxService fxService;
    private final BigDecimal perPo;
    private final BigDecimal perInvoice;
    private final BigDecimal thresholdUsd;
    private final BigDecimal percentAbove;

    public PlatformFeeService(PlatformFeeRepository platformFeeRepository, CompanyRepository companyRepository,
                               InvoiceRepository invoiceRepository, FxService fxService,
                               @Value("${kixima.fees.per-po-usd:8}") BigDecimal perPo,
                               @Value("${kixima.fees.per-invoice-usd:15}") BigDecimal perInvoice,
                               @Value("${kixima.fees.threshold-usd:11500}") BigDecimal thresholdUsd,
                               @Value("${kixima.fees.percent-above:0.002}") BigDecimal percentAbove) {
        this.platformFeeRepository = platformFeeRepository;
        this.companyRepository = companyRepository;
        this.invoiceRepository = invoiceRepository;
        this.fxService = fxService;
        this.perPo = perPo;
        this.perInvoice = perInvoice;
        this.thresholdUsd = thresholdUsd;
        this.percentAbove = percentAbove;
    }

    private static BigDecimal round2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal perPo() {
        return perPo;
    }

    public BigDecimal perInvoice() {
        return perInvoice;
    }

    public BigDecimal thresholdUsd() {
        return thresholdUsd;
    }

    public BigDecimal percentAbove() {
        return percentAbove;
    }

    /** Calcula a taxa de uma fatura — {@code poCount} POs cobertas, {@code poValueUsd} valor (por PO) em USD, que decide o limiar. */
    public Calculo compute(int poCount, BigDecimal poValueUsd) {
        int count = Math.max(1, poCount);
        BigDecimal valor = poValueUsd == null ? BigDecimal.ZERO : poValueUsd;
        boolean acima = valor.compareTo(thresholdUsd) > 0;
        BigDecimal parcelaPo = acima ? round2(valor.multiply(percentAbove)) : perPo;
        String basis = acima ? "PERCENTUAL" : "FIXO";
        // Acima do limiar, os 0,20 % são cobrados no fim e já INCLUEM a parcela da fatura.
        BigDecimal parcelaFatura = acima ? BigDecimal.ZERO : perInvoice;
        BigDecimal amount = round2(parcelaPo.multiply(BigDecimal.valueOf(count)).add(parcelaFatura));
        return new Calculo(count, parcelaPo, parcelaFatura, amount, CURRENCY, basis, round2(valor));
    }

    /**
     * Cria o registo de taxa para uma fatura, na MESMA transação do pagamento (quem chama é @Transactional).
     * O nº de POs é 1 (fatura normal) ou o nº de call-offs consolidados — `consolidatedPoIds?.length || 1`.
     */
    public PlatformFee createForInvoice(Invoice invoice, String companyId) {
        List<String> consolidadas = invoice.getConsolidatedPoIds();
        int poCount = consolidadas == null || consolidadas.isEmpty() ? 1 : consolidadas.size();
        BigDecimal rate = fxService.fxRate();
        // O limiar aplica-se POR TRANSAÇÃO: numa fatura consolidada, o total do período é dividido pelo nº de POs.
        BigDecimal totalUsd = fxService.toUsd(invoice.getAmount(), invoice.getCurrency());
        BigDecimal perPoValueUsd = round2(totalUsd.divide(BigDecimal.valueOf(poCount), 2, RoundingMode.HALF_UP));
        Calculo f = compute(poCount, perPoValueUsd);
        PlatformFee fee = new PlatformFee(UUID.randomUUID().toString(), companyId, invoice.getId(), f.poCount(), f.perPo(),
                f.perInvoice(), f.amount(), f.currency(), f.basis(), f.poValueUsd(), rate, Instant.now());
        return platformFeeRepository.save(fee);
    }

    private static BigDecimal soma(List<PlatformFee> fees) {
        return round2(fees.stream().map(PlatformFee::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private Map<String, Invoice> faturasDe(List<PlatformFee> fees) {
        List<String> ids = fees.stream().map(PlatformFee::getInvoiceId).toList();
        return ids.isEmpty() ? Map.of() : invoiceRepository.findAllById(ids).stream().collect(Collectors.toMap(Invoice::getId, i -> i));
    }

    /** Extrato de taxas de UMA empresa (fornecedor): "quanto devo à KIXIMA e porquê", e o documento de cobrança do Admin. */
    @Transactional(readOnly = true)
    public PlatformFeeStatementDto statementFor(String companyId) {
        Company company = companyRepository.findById(companyId).orElseThrow(() -> new NotFoundException("Empresa"));
        List<PlatformFee> fees = platformFeeRepository.findByCompanyIdOrderByCreatedAtDesc(companyId);
        Map<String, Invoice> faturas = faturasDe(fees);

        List<PlatformFee> pendentes = fees.stream().filter(f -> f.getStatus() == PlatformFeeStatus.PENDENTE).toList();
        List<PlatformFee> cobradas = fees.stream().filter(f -> f.getStatus() == PlatformFeeStatus.COBRADO).toList();

        return new PlatformFeeStatementDto(
                new PlatformFeeStatementDto.CompanyRef(company.getId(), company.getName(), company.getTaxId(), company.getAddress(),
                        company.getCity(), company.getProvince(), company.getCountry(), company.getContactEmail(),
                        company.getPlan() == null ? null : company.getPlan().name(), company.getSize() == null ? null : company.getSize().name(),
                        company.getSeatPriceUsd()),
                fees.stream().map(f -> PlatformFeeDto.de(f, null, faturas.get(f.getInvoiceId()))).toList(),
                new PlatformFeeStatementDto.Kpis(fees.size(), soma(fees), soma(pendentes), soma(cobradas), pendentes.size(), cobradas.size(), CURRENCY),
                new PlatformFeeStatementDto.Formula(perPo, perInvoice, thresholdUsd, percentAbove, CURRENCY),
                Instant.now());
    }

    /** Livro de taxas da plataforma — todas as taxas geradas nos pagamentos (adminService.listPlatformFees). */
    @Transactional(readOnly = true)
    public PlatformFeeBookDto listPlatformFees() {
        List<PlatformFee> fees = platformFeeRepository.findAllByOrderByCreatedAtDesc();
        Map<String, Invoice> faturas = faturasDe(fees);
        List<String> companyIds = fees.stream().map(PlatformFee::getCompanyId).distinct().toList();
        Map<String, Company> empresas = companyIds.isEmpty() ? Map.of()
                : companyRepository.findAllById(companyIds).stream().collect(Collectors.toMap(Company::getId, c -> c));

        List<PlatformFee> pendentes = fees.stream().filter(f -> f.getStatus() == PlatformFeeStatus.PENDENTE).toList();
        long cobradas = fees.stream().filter(f -> f.getStatus() == PlatformFeeStatus.COBRADO).count();
        return new PlatformFeeBookDto(
                fees.stream().map(f -> PlatformFeeDto.de(f, empresas.get(f.getCompanyId()), faturas.get(f.getInvoiceId()))).toList(),
                new PlatformFeeBookDto.Kpis(fees.size(), soma(fees), soma(pendentes), (int) cobradas));
    }

    /** Marcar uma taxa como cobrada (enquanto não há débito automático). */
    @Transactional
    public PlatformFeeDto chargePlatformFee(String id) {
        PlatformFee fee = platformFeeRepository.findById(id).orElseThrow(() -> new NotFoundException("Taxa"));
        fee.marcarCobrada(Instant.now());
        return PlatformFeeDto.de(fee, null, null);
    }
}
