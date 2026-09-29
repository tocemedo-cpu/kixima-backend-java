package ao.kixima.painel;

import ao.kixima.common.Decimais;

import ao.kixima.company.Company;
import ao.kixima.contract.Contract;
import ao.kixima.contract.ContractRepository;
import ao.kixima.invoice.Invoice;
import ao.kixima.invoice.InvoiceRepository;
import ao.kixima.invoice.InvoiceStatus;
import ao.kixima.payment.Payment;
import ao.kixima.payment.PaymentRepository;
import ao.kixima.po.PurchaseOrder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static ao.kixima.painel.CompanyAdminPanelService.mapa;

/**
 * Espelha financeiroService.js — telas do Financeiro (Centro Financeiro,
 * Faturas Pendentes, Pagamentos): agregações das faturas da empresa
 * compradora (via PO ou contrato).
 */
@Service
public class FinanceiroPanelService {

    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final ContractRepository contractRepository;

    public FinanceiroPanelService(InvoiceRepository invoiceRepository, PaymentRepository paymentRepository, ContractRepository contractRepository) {
        this.invoiceRepository = invoiceRepository;
        this.paymentRepository = paymentRepository;
        this.contractRepository = contractRepository;
    }

    /** Uma fatura com o que o `include` do Node lhe pendura: PO (com fornecedor), contrato e pagamento. */
    record Carregada(Invoice inv, PurchaseOrder po, Contract contract, Payment payment) {
        String supplier() {
            Company s = po != null ? po.getSupplierCompany() : contract != null ? contract.getSupplierCompany() : null;
            return s == null ? "—" : s.getName();
        }

        Map<String, Object> shape() {
            return mapa("id", inv.getId(), "reference", inv.getReference(), "supplier", supplier(),
                    "poReference", po != null ? po.getReference() : contract != null ? contract.getReference() : null,
                    "poId", inv.getPurchaseOrderId() != null ? inv.getPurchaseOrderId() : po != null ? po.getId() : null,
                    "amount", Decimais.numero(inv.getAmount()), "currency", inv.getCurrency(), "status", inv.getStatus().name(),
                    "issuedAt", inv.getIssuedAt(), "dueAt", inv.getDueAt(),
                    "paidAt", payment == null ? null : payment.getProcessedAt());
        }

        boolean pagaDesde(Instant desde) {
            return inv.getStatus() == InvoiceStatus.PAGA && payment != null && payment.getProcessedAt() != null && !payment.getProcessedAt().isBefore(desde);
        }
    }

    List<Carregada> loadInvoices(String companyId) {
        List<Invoice> invoices = invoiceRepository.findDaEmpresaCompradora(companyId);
        Map<String, Payment> pagamentos = paymentRepository.findByInvoiceIdIn(invoices.stream().map(Invoice::getId).toList()).stream()
                .collect(Collectors.toMap(Payment::getInvoiceId, p -> p, (a, b) -> a));
        Map<String, Contract> contratos = contractRepository.findAllById(invoices.stream().map(Invoice::getContractId).filter(id -> id != null).distinct().toList())
                .stream().collect(Collectors.toMap(Contract::getId, c -> c));
        List<Carregada> out = new ArrayList<>();
        for (Invoice i : invoices) {
            out.add(new Carregada(i, i.getPurchaseOrder(), i.getContractId() == null ? null : contratos.get(i.getContractId()), pagamentos.get(i.getId())));
        }
        return out;
    }

    /** `num()` do financeiroService.js: as somas saem como número. */
    static Number soma(List<Carregada> is) {
        return Decimais.numero(is.stream().map(c -> c.inv().getAmount()).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static List<Carregada> pendentes(List<Carregada> all) {
        return all.stream().filter(c -> c.inv().getStatus() == InvoiceStatus.PENDENTE).toList();
    }

    /** Centro Financeiro (dashboard). */
    @Transactional(readOnly = true)
    public Map<String, Object> overview(String companyId) {
        List<Carregada> invoices = loadInvoices(companyId);
        Instant now = Instant.now();
        Instant monthStart = Meses.inicioDoMes();
        Instant in7 = now.plus(7, ChronoUnit.DAYS);
        List<Carregada> pend = pendentes(invoices);
        List<Carregada> pagasMes = invoices.stream().filter(c -> c.pagaDesde(monthStart)).toList();

        List<Map<String, Object>> series = Meses.ultimosSeis().stream().map(m -> mapa("label", m.label(),
                "faturas", soma(invoices.stream().filter(c -> m.contem(c.inv().getIssuedAt())).toList()),
                "pagamentos", soma(invoices.stream().filter(c -> c.payment() != null && m.contem(c.payment().getProcessedAt())).toList()))).toList();

        return mapa("kpis", mapa("pagamentosPendentes", soma(pend), "pagamentosPendentesCount", pend.size(),
                        "faturasRecebidas", invoices.size(), "pagosMes", soma(pagasMes), "aprovacoesPendentes", pend.size(),
                        "aVencer7", pend.stream().filter(c -> !c.inv().getDueAt().isAfter(in7)).count()),
                "series", series,
                "pendentes", pend.stream().limit(5).map(Carregada::shape).toList());
    }

    /** Faturas Pendentes. */
    @Transactional(readOnly = true)
    public Map<String, Object> invoices(String companyId) {
        List<Carregada> all = loadInvoices(companyId);
        Instant now = Instant.now();
        Instant monthStart = Meses.inicioDoMes();
        Instant in7 = now.plus(7, ChronoUnit.DAYS);
        List<Carregada> pend = pendentes(all);
        return mapa("kpis", mapa("pendentes", pend.size(), "valorPendente", soma(pend),
                        "aVencer7", pend.stream().filter(c -> !c.inv().getDueAt().isAfter(in7)).count(),
                        "vencidas", pend.stream().filter(c -> c.inv().getDueAt().isBefore(now)).count(),
                        "aprovadasMes", all.stream().filter(c -> c.pagaDesde(monthStart)).count()),
                "items", pend.stream().map(Carregada::shape).toList());
    }

    /** Pagamentos (histórico + pendentes). */
    @Transactional(readOnly = true)
    public Map<String, Object> payments(String companyId, String status) {
        List<Carregada> all = loadInvoices(companyId);
        Instant now = Instant.now();
        Instant monthStart = Meses.inicioDoMes();
        List<Carregada> rows = all;
        if ("PENDENTE".equals(status)) rows = pendentes(all);
        else if ("PAGO".equals(status)) rows = all.stream().filter(c -> c.inv().getStatus() == InvoiceStatus.PAGA).toList();
        else if ("VENCIDO".equals(status)) rows = all.stream().filter(c -> c.inv().getStatus() == InvoiceStatus.VENCIDA
                || (c.inv().getStatus() == InvoiceStatus.PENDENTE && c.inv().getDueAt().isBefore(now))).toList();
        return mapa("kpis", mapa("aPagar", soma(pendentes(all)),
                        "pagosMes", soma(all.stream().filter(c -> c.pagaDesde(monthStart)).toList()),
                        "vencidos", soma(pendentes(all).stream().filter(c -> c.inv().getDueAt().isBefore(now)).toList()),
                        "total", all.size()),
                "items", rows.stream().map(Carregada::shape).toList());
    }
}
