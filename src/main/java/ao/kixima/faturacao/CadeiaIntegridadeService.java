package ao.kixima.faturacao;

import ao.kixima.invoice.Invoice;
import ao.kixima.invoice.InvoiceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Espelha faturacaoService.verificarCadeia — percorre uma série inteira e
 * relata TODOS os problemas (buracos na numeração, elos partidos, documentos
 * alterados depois de emitidos), não só o primeiro: parar no primeiro
 * obrigaria a corrigir e repetir N vezes para ver N problemas.
 */
@Service
public class CadeiaIntegridadeService {

    private final InvoiceRepository invoiceRepository;
    private final FaturacaoService faturacaoService;

    public CadeiaIntegridadeService(InvoiceRepository invoiceRepository, FaturacaoService faturacaoService) {
        this.invoiceRepository = invoiceRepository;
        this.faturacaoService = faturacaoService;
    }

    private static Map<String, Object> problema(String tipo, String fatura, String detalhe) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tipo", tipo);
        m.put("fatura", fatura);
        m.put("detalhe", detalhe);
        return m;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> verificarCadeia(String codigo, int ano) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (codigo == null || codigo.isBlank()) {
            out.put("serie", null);
            out.put("verificada", false);
            out.put("motivo", "Indique a série a verificar (é por fornecedor, já não há uma série global única).");
            return out;
        }
        List<Invoice> faturas = invoiceRepository.findBySerieAndNumeroNaSerieIsNotNullOrderByNumeroNaSerieAsc(codigo);
        List<Map<String, Object>> problemas = new ArrayList<>();
        String esperado = null;
        for (int i = 0; i < faturas.size(); i++) {
            Invoice f = faturas.get(i);
            int numeroEsperado = i + 1;
            if (f.getNumeroNaSerie() != numeroEsperado) {
                problemas.add(problema("BURACO_NA_NUMERACAO", f.getReference(),
                        "esperava o número " + numeroEsperado + " e encontrou " + f.getNumeroNaSerie()));
            }
            if (!Objects.equals(f.getHashAnterior(), esperado)) {
                problemas.add(problema("ELO_PARTIDO", f.getReference(), "o hash anterior não corresponde ao documento que a precede"));
            }
            String recalculado = faturacaoService.calcularHash(f.getIssuedAt(), codigo, f.getNumeroNaSerie(), f.getAmount(), f.getHashAnterior());
            if (!recalculado.equals(f.getHashDocumento())) {
                problemas.add(problema("DOCUMENTO_ALTERADO", f.getReference(),
                        "o hash não bate com o conteúdo — a fatura foi alterada depois de emitida"));
            }
            esperado = f.getHashDocumento();
        }
        out.put("serie", codigo);
        out.put("ano", ano);
        out.put("documentos", faturas.size());
        out.put("integra", problemas.isEmpty());
        out.put("problemas", problemas);
        return out;
    }
}
