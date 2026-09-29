package ao.kixima.faturacao;

import ao.kixima.common.error.ValidationException;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyRepository;
import ao.kixima.contract.Contract;
import ao.kixima.contract.ContractRepository;
import ao.kixima.creditnote.CreditNote;
import ao.kixima.creditnote.CreditNoteRepository;
import ao.kixima.invoice.Invoice;
import ao.kixima.invoice.InvoiceLine;
import ao.kixima.invoice.InvoiceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Espelha backend/src/services/saftService.js — SAF-T (AO) de UMA empresa
 * fornecedora, num período: faturas (FT) e notas de crédito (NC) com as
 * linhas, os clientes, os totais e o que ficou por configurar (dito, nunca
 * inventado: um número de certificado falso num ficheiro fiscal é uma
 * declaração falsa).
 *
 * Um período ilegível ou uma empresa em falta são erros do pedido (422) nos
 * dois backends — o Node devolvia 500 com um `Error` cru e foi alinhado.
 */
@Service
public class SaftService {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter CARIMBO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    public record Periodo(Instant ini, Instant fim) {
    }

    public record Resultado(String xml, Map<String, Object> resumo) {
    }

    private final InvoiceRepository invoiceRepository;
    private final CreditNoteRepository creditNoteRepository;
    private final CompanyRepository companyRepository;
    private final ContractRepository contractRepository;
    private final String nifKixima;
    private final String certificadoAgt;
    private final String versao;

    public SaftService(InvoiceRepository invoiceRepository, CreditNoteRepository creditNoteRepository,
                       CompanyRepository companyRepository, ContractRepository contractRepository,
                       @Value("${kixima.faturacao.nif:}") String nifKixima,
                       @Value("${kixima.faturacao.certificado-agt:}") String certificadoAgt,
                       @Value("${kixima.versao:1.0}") String versao) {
        this.invoiceRepository = invoiceRepository;
        this.creditNoteRepository = creditNoteRepository;
        this.companyRepository = companyRepository;
        this.contractRepository = contractRepository;
        this.nifKixima = nifKixima == null ? "" : nifKixima.trim();
        this.certificadoAgt = certificadoAgt == null ? "" : certificadoAgt.trim();
        this.versao = versao == null || versao.isBlank() ? "1.0" : versao;
    }

    // --- Auxiliares de XML -------------------------------------------------------

    static String esc(Object v) {
        return String.valueOf(v == null ? "" : v)
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String el(String nome, Object valor) {
        if (valor == null || String.valueOf(valor).isEmpty()) return "";
        return "<" + nome + ">" + esc(valor) + "</" + nome + ">";
    }

    private static String dinheiro(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** `String(li.quantity)` de um Decimal do Prisma — sem zeros à direita. */
    private static String quantidade(BigDecimal q) {
        return q == null ? "0" : q.stripTrailingZeros().toPlainString();
    }

    private static Instant parseData(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim()).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception e) {
            try {
                return Instant.parse(s.trim());
            } catch (Exception e2) {
                return null;
            }
        }
    }

    public static Periodo validarPeriodo(String de, String ate) {
        Instant ini = parseData(de);
        Instant fim = parseData(ate);
        if (ini == null || fim == null) throw new ValidationException("Indique o período no formato AAAA-MM-DD (de e até).");
        if (fim.isBefore(ini)) throw new ValidationException("A data final tem de ser posterior à inicial.");
        // setHours(23, 59, 59, 999) — o fim do dia, inclusive.
        Instant fimDoDia = fim.atZone(ZoneOffset.UTC).toLocalDate().atTime(23, 59, 59, 999_000_000).toInstant(ZoneOffset.UTC);
        return new Periodo(ini, fimDoDia);
    }

    // --- Cliente de cada documento -------------------------------------------------

    private Company clienteDe(Invoice f, Map<String, Contract> contratos) {
        if (f.getPurchaseOrder() != null && f.getPurchaseOrder().getBuyerCompany() != null) return f.getPurchaseOrder().getBuyerCompany();
        if (f.getContractId() != null) {
            Contract c = contratos.get(f.getContractId());
            if (c != null) return c.getClientCompany();
        }
        return null;
    }

    private static void cliente(List<String> linhas, Company c) {
        linhas.add("    <Customer>");
        linhas.add("      " + el("CustomerID", c.getId()));
        linhas.add("      " + el("AccountID", "Desconhecido"));
        linhas.add("      " + el("CustomerTaxID", c.getTaxId()));
        linhas.add("      " + el("CompanyName", c.getName()));
        linhas.add("      <BillingAddress>");
        linhas.add("        " + el("AddressDetail", c.getAddress() == null || c.getAddress().isBlank() ? "Desconhecido" : c.getAddress()));
        linhas.add("        " + el("City", c.getCity() == null || c.getCity().isBlank() ? "Desconhecido" : c.getCity()));
        linhas.add("        " + el("Country", c.getCountry() == null || c.getCountry().isBlank() ? "AO" : c.getCountry()));
        linhas.add("      </BillingAddress>");
        linhas.add("    </Customer>");
    }

    private static void estadoDoDocumento(List<String> linhas, String estado, Instant quando) {
        linhas.add("        <DocumentStatus>");
        linhas.add("          " + el("InvoiceStatus", estado));
        linhas.add("          " + el("InvoiceStatusDate", CARIMBO.format(quando)));
        linhas.add("          " + el("SourceID", "KIXIMA"));
        linhas.add("          " + el("SourceBilling", "P"));
        linhas.add("        </DocumentStatus>");
    }

    private static void regimes(List<String> linhas) {
        linhas.add("        <SpecialRegimes>");
        linhas.add("          " + el("SelfBillingIndicator", "0"));
        linhas.add("          " + el("CashVATSchemeIndicator", "0"));
        linhas.add("          " + el("ThirdPartiesBillingIndicator", "0"));
        linhas.add("        </SpecialRegimes>");
    }

    private static void totais(List<String> linhas, BigDecimal taxPayable, BigDecimal netTotal, BigDecimal grossTotal) {
        linhas.add("        <DocumentTotals>");
        linhas.add("          " + el("TaxPayable", dinheiro(taxPayable)));
        linhas.add("          " + el("NetTotal", dinheiro(netTotal)));
        linhas.add("          " + el("GrossTotal", dinheiro(grossTotal)));
        linhas.add("        </DocumentTotals>");
    }

    private static int ano(Instant i) {
        return i.atZone(ZoneOffset.UTC).getYear();
    }

    @Transactional(readOnly = true)
    public Resultado gerar(String de, String ate, String supplierCompanyId) {
        Periodo periodo = validarPeriodo(de, ate);
        Instant ini = periodo.ini();
        Instant fim = periodo.fim();

        if (supplierCompanyId == null || supplierCompanyId.isBlank()) {
            throw new ValidationException("Indique a empresa fornecedora (supplierCompanyId) — o SAF-T é sempre de uma só empresa.");
        }
        Company fornecedor = companyRepository.findById(supplierCompanyId)
                .orElseThrow(() -> new ValidationException("Empresa fornecedora não encontrada."));

        List<Invoice> faturas = invoiceRepository.findDoFornecedorNoPeriodo(supplierCompanyId, ini, fim);
        List<CreditNote> notasDoPeriodo = creditNoteRepository.findDoFornecedorNoPeriodo(supplierCompanyId, ini, fim);

        // Contratos-quadro referenciados (as faturas consolidadas não têm PO).
        Map<String, Contract> contratos = new HashMap<>();
        List<String> contractIds = new ArrayList<>();
        for (Invoice f : faturas) if (f.getContractId() != null) contractIds.add(f.getContractId());
        for (CreditNote n : notasDoPeriodo) if (n.getInvoice() != null && n.getInvoice().getContractId() != null) contractIds.add(n.getInvoice().getContractId());
        if (!contractIds.isEmpty()) for (Contract c : contractRepository.findAllById(contractIds)) contratos.put(c.getId(), c);

        // Total creditado por fatura (todas as notas, não só as do período) — decide o estado A/N.
        Map<String, BigDecimal> creditadoPorFatura = new HashMap<>();
        if (!faturas.isEmpty()) {
            for (CreditNote n : creditNoteRepository.findByInvoiceIdIn(faturas.stream().map(Invoice::getId).toList())) {
                creditadoPorFatura.merge(n.getInvoiceId(), n.getAmount() == null ? BigDecimal.ZERO : n.getAmount(), BigDecimal::add);
            }
        }

        Map<String, Company> clientes = new LinkedHashMap<>();
        for (Invoice f : faturas) {
            Company c = clienteDe(f, contratos);
            if (c != null) clientes.putIfAbsent(c.getId(), c);
        }
        for (CreditNote n : notasDoPeriodo) {
            Company c = n.getInvoice() == null ? null : clienteDe(n.getInvoice(), contratos);
            if (c != null) clientes.putIfAbsent(c.getId(), c);
        }

        BigDecimal totalTributavel = BigDecimal.ZERO;
        BigDecimal totalImposto = BigDecimal.ZERO;
        BigDecimal totalDocumentos = BigDecimal.ZERO;
        int semSerie = 0;
        for (Invoice f : faturas) {
            totalTributavel = totalTributavel.add(f.getNetAmount() == null ? BigDecimal.ZERO : f.getNetAmount());
            totalImposto = totalImposto.add(f.getTaxAmount() == null ? BigDecimal.ZERO : f.getTaxAmount());
            totalDocumentos = totalDocumentos.add(f.getAmount() == null ? BigDecimal.ZERO : f.getAmount());
            if (f.getSerie() == null || f.getSerie().isBlank()) semSerie++;
        }

        List<String> linhas = new ArrayList<>();
        linhas.add("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        linhas.add("<AuditFile xmlns=\"urn:OECD:StandardAuditFile-Tax:AO_1.01_01\">");

        String taxId = fornecedor.getTaxId() == null || fornecedor.getTaxId().isBlank() ? "POR-CONFIGURAR" : fornecedor.getTaxId();
        linhas.add("  <Header>");
        linhas.add("    " + el("AuditFileVersion", "1.01_01"));
        linhas.add("    " + el("CompanyID", taxId));
        linhas.add("    " + el("TaxRegistrationNumber", taxId));
        linhas.add("    " + el("TaxAccountingBasis", "F"));
        linhas.add("    " + el("CompanyName", fornecedor.getName()));
        linhas.add("    " + el("FiscalYear", String.valueOf(ano(ini))));
        linhas.add("    " + el("StartDate", DATA.format(ini)));
        linhas.add("    " + el("EndDate", DATA.format(fim)));
        linhas.add("    " + el("CurrencyCode", "AOA"));
        linhas.add("    " + el("DateCreated", DATA.format(Instant.now())));
        linhas.add("    " + el("TaxEntity", "Global"));
        linhas.add("    " + el("ProductCompanyTaxID", nifKixima.isBlank() ? "POR-CONFIGURAR" : nifKixima));
        linhas.add("    " + el("SoftwareCertificateNumber", certificadoAgt.isBlank() ? "0" : certificadoAgt));
        linhas.add("    " + el("ProductID", "KIXIMA"));
        linhas.add("    " + el("ProductVersion", versao));
        linhas.add("  </Header>");

        linhas.add("  <MasterFiles>");
        for (Company c : clientes.values()) cliente(linhas, c);
        linhas.add("  </MasterFiles>");

        linhas.add("  <SourceDocuments>");
        linhas.add("    <SalesInvoices>");
        linhas.add("      " + el("NumberOfEntries", String.valueOf(faturas.size() + notasDoPeriodo.size())));
        linhas.add("      " + el("TotalDebit", dinheiro(BigDecimal.ZERO)));
        linhas.add("      " + el("TotalCredit", dinheiro(totalTributavel)));

        for (Invoice f : faturas) {
            Company c = clienteDe(f, contratos);
            String numero = FaturacaoService.numeroDocumentoAGT(f.getSerie(), ano(f.getIssuedAt()), f.getNumeroNaSerie());
            if (numero == null) numero = f.getReference();
            BigDecimal creditado = creditadoPorFatura.getOrDefault(f.getId(), BigDecimal.ZERO);
            boolean anulada = creditado.signum() > 0 && creditado.compareTo(f.getAmount() == null ? BigDecimal.ZERO : f.getAmount()) >= 0;

            linhas.add("      <Invoice>");
            linhas.add("        " + el("InvoiceNo", numero));
            estadoDoDocumento(linhas, anulada ? "A" : "N", f.getUpdatedAt() != null ? f.getUpdatedAt() : f.getIssuedAt());
            linhas.add("        " + el("Hash", f.getHashDocumento() == null ? "" : f.getHashDocumento()));
            linhas.add("        " + el("HashControl", "1"));
            linhas.add("        " + el("InvoiceDate", DATA.format(f.getIssuedAt())));
            linhas.add("        " + el("InvoiceType", "FT"));
            regimes(linhas);
            linhas.add("        " + el("SourceID", "KIXIMA"));
            linhas.add("        " + el("SystemEntryDate", CARIMBO.format(f.getCreatedAt())));
            linhas.add("        " + el("CustomerID", c == null ? "Desconhecido" : c.getId()));
            List<InvoiceLine> lines = new ArrayList<>(f.getLines());
            lines.sort(Comparator.comparingInt(InvoiceLine::getLineNumber));
            for (InvoiceLine li : lines) {
                linhas.add("        <Line>");
                linhas.add("          " + el("LineNumber", String.valueOf(li.getLineNumber())));
                linhas.add("          " + el("ProductCode", li.getProductCode()));
                linhas.add("          " + el("ProductDescription", li.getDescription()));
                linhas.add("          " + el("Quantity", quantidade(li.getQuantity())));
                linhas.add("          " + el("UnitPrice", dinheiro(li.getUnitPrice())));
                linhas.add("          " + el("CreditAmount", dinheiro(li.getNetAmount())));
                linhas.add("          <Tax>");
                linhas.add("            " + el("TaxCode", li.getIvaTaxCode()));
                linhas.add("            " + el("TaxAmount", dinheiro(li.getIvaAmount())));
                linhas.add("          </Tax>");
                linhas.add("        </Line>");
            }
            totais(linhas, f.getTaxAmount(), f.getNetAmount(), f.getAmount());
            linhas.add("      </Invoice>");
        }

        for (CreditNote n : notasDoPeriodo) {
            Invoice fat = n.getInvoice();
            Company c = fat == null ? null : clienteDe(fat, contratos);
            String numero = FaturacaoService.numeroDocumentoAGT(n.getSerie(), ano(n.getIssuedAt()), n.getNumeroNaSerie());
            if (numero == null) numero = n.getReference();
            String faturaOriginal = null;
            if (fat != null) {
                faturaOriginal = FaturacaoService.numeroDocumentoAGT(fat.getSerie(),
                        ano(fat.getIssuedAt() != null ? fat.getIssuedAt() : n.getIssuedAt()), fat.getNumeroNaSerie());
                if (faturaOriginal == null) faturaOriginal = fat.getReference();
            }

            linhas.add("      <Invoice>");
            linhas.add("        " + el("InvoiceNo", numero));
            estadoDoDocumento(linhas, "N", n.getCreatedAt() != null ? n.getCreatedAt() : n.getIssuedAt());
            linhas.add("        " + el("Hash", n.getHashDocumento() == null ? "" : n.getHashDocumento()));
            linhas.add("        " + el("HashControl", "1"));
            linhas.add("        " + el("InvoiceDate", DATA.format(n.getIssuedAt())));
            linhas.add("        " + el("InvoiceType", "NC"));
            regimes(linhas);
            linhas.add("        " + el("SourceID", "KIXIMA"));
            linhas.add("        " + el("SystemEntryDate", CARIMBO.format(n.getCreatedAt() != null ? n.getCreatedAt() : n.getIssuedAt())));
            linhas.add("        " + el("CustomerID", c == null ? "Desconhecido" : c.getId()));
            linhas.add("        <References>");
            linhas.add("          " + el("Reference", faturaOriginal));
            linhas.add("          " + el("Reason", n.getMotivo()));
            linhas.add("        </References>");
            totais(linhas, n.getTaxAmount(), n.getNetAmount(), n.getAmount());
            linhas.add("      </Invoice>");
        }

        linhas.add("    </SalesInvoices>");
        linhas.add("  </SourceDocuments>");
        linhas.add("</AuditFile>");

        StringBuilder xml = new StringBuilder();
        for (String l : linhas) {
            if (l.trim().isEmpty()) continue;
            if (xml.length() > 0) xml.append('\n');
            xml.append(l);
        }

        List<String> porConfigurar = new ArrayList<>();
        if (fornecedor.getTaxId() == null || fornecedor.getTaxId().isBlank()) porConfigurar.add("NIF do fornecedor (Company.taxId)");
        if (fornecedor.getSerieFiscal() == null || fornecedor.getSerieFiscal().isBlank()) porConfigurar.add("Série certificada do fornecedor (Company.serieFiscal)");
        if (nifKixima.isBlank()) porConfigurar.add("KIXIMA_NIF");
        if (certificadoAgt.isBlank()) porConfigurar.add("KIXIMA_CERTIFICADO_AGT");

        Map<String, Object> fornecedorOut = new LinkedHashMap<>();
        fornecedorOut.put("id", fornecedor.getId());
        fornecedorOut.put("nome", fornecedor.getName());
        Map<String, Object> periodoOut = new LinkedHashMap<>();
        periodoOut.put("de", DATA.format(ini));
        periodoOut.put("ate", DATA.format(fim));
        Map<String, Object> resumo = new LinkedHashMap<>();
        resumo.put("fornecedor", fornecedorOut);
        resumo.put("periodo", periodoOut);
        resumo.put("documentos", faturas.size());
        resumo.put("clientes", clientes.size());
        resumo.put("totalTributavel", totalTributavel.setScale(2, RoundingMode.HALF_UP));
        resumo.put("totalImposto", totalImposto.setScale(2, RoundingMode.HALF_UP));
        resumo.put("totalDocumentos", totalDocumentos.setScale(2, RoundingMode.HALF_UP));
        resumo.put("semSerieCertificada", semSerie);
        resumo.put("porConfigurar", porConfigurar);
        return new Resultado(xml.toString(), resumo);
    }
}
