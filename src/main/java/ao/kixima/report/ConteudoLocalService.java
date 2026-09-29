package ao.kixima.report;

import ao.kixima.common.error.BusinessRuleException;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyRepository;
import ao.kixima.company.CompanySize;
import ao.kixima.po.PoStatus;
import ao.kixima.po.PurchaseOrder;
import ao.kixima.po.PurchaseOrderItem;
import ao.kixima.po.PurchaseOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Espelha conteudoLocalService.js — relatório de conteúdo local (desenho
 * proposto, sem modelo oficial da ANPG). Três perguntas: contratação
 * nacional, origem do bem (por linha) e MPME angolana; cada percentagem
 * reconstitui-se a partir do anexo.
 */
@Service
public class ConteudoLocalService {

    /** Só entram ordens com compromisso financeiro real. */
    public static final List<PoStatus> ESTADOS_CONTAM = List.of(PoStatus.ACEITE_FORNECEDOR, PoStatus.AGUARDANDO_PAGAMENTO, PoStatus.PAGA,
            PoStatus.EM_EXECUCAO, PoStatus.ENTREGUE, PoStatus.RECEBIDA_CONFORME, PoStatus.RECEBIDA_COM_DIVERGENCIA, PoStatus.CONCLUIDA);
    private static final String ANGOLA = "angola";
    private static final Set<CompanySize> MPME = Set.of(CompanySize.MICRO, CompanySize.PEQUENA, CompanySize.MEDIA);

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final CompanyRepository companyRepository;

    public ConteudoLocalService(PurchaseOrderRepository purchaseOrderRepository, CompanyRepository companyRepository) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.companyRepository = companyRepository;
    }

    private static boolean ePais(String valor, String pais) {
        return valor != null && valor.trim().toLowerCase().equals(pais);
    }

    private static boolean eAngolana(Company empresa) {
        return empresa != null && ePais(empresa.getCountry(), ANGOLA);
    }

    /** Valor SEM IVA. O imposto é do Estado, não do fornecedor. */
    static BigDecimal valorDaOrdem(PurchaseOrder po) {
        if (po.getNetAmount() != null) return po.getNetAmount();
        return po.getTotalAmount() == null ? BigDecimal.ZERO : po.getTotalAmount();
    }

    /** Uma casa decimal, como `Math.round(x * 1000) / 10`. */
    static double percentagem(BigDecimal parte, BigDecimal total) {
        if (total == null || total.signum() == 0) return 0;
        return parte.multiply(BigDecimal.valueOf(1000)).divide(total, 0, RoundingMode.HALF_UP).doubleValue() / 10;
    }

    record Origem(BigDecimal angolana, BigDecimal importada, BigDecimal porDeclarar) {
        Map<String, Object> mapa() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("angolana", angolana);
            m.put("importada", importada);
            m.put("porDeclarar", porDeclarar);
            return m;
        }
    }

    /**
     * Origem do valor de UMA ordem, ao nível da linha. Uma linha sem país de
     * origem declarado NÃO conta como angolana — o desconhecido não pode
     * contar a favor de quem reporta.
     */
    static Origem origemDasLinhas(PurchaseOrder po) {
        BigDecimal bruto = po.getTotalAmount() == null ? BigDecimal.ZERO : po.getTotalAmount();
        BigDecimal liquido = valorDaOrdem(po);
        // Fator para converter valores de linha na mesma base do total da ordem, quando a ordem traz IVA
        // mas não tem líquido guardado (`liquido && !po.netAmount`).
        BigDecimal escala = bruto.signum() != 0 && liquido.signum() != 0 && po.getNetAmount() == null
                ? liquido.divide(bruto, 10, RoundingMode.HALF_UP) : BigDecimal.ONE;
        BigDecimal angolana = BigDecimal.ZERO, importada = BigDecimal.ZERO, porDeclarar = BigDecimal.ZERO;
        for (PurchaseOrderItem item : po.getItems()) {
            BigDecimal linha = (item.getLineTotal() == null ? BigDecimal.ZERO : item.getLineTotal()).multiply(escala);
            String origem = item.getProduct() == null ? null : item.getProduct().getCountryOfOrigin();
            if (origem == null || origem.isBlank()) porDeclarar = porDeclarar.add(linha);
            else if (ePais(origem, ANGOLA)) angolana = angolana.add(linha);
            else importada = importada.add(linha);
        }
        return new Origem(angolana, importada, porDeclarar);
    }

    static Instant data(String v, boolean fim) {
        String t = v.trim();
        try {
            return Instant.parse(t);
        } catch (DateTimeParseException ignorado) {
            try {
                LocalDate d = LocalDate.parse(t);
                return d.atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException e) {
                throw new BusinessRuleException("Datas inválidas. Use o formato AAAA-MM-DD.");
            }
        }
    }

    /** Relatório de um período — {@code de}/{@code ate} ISO; o fim é inclusivo (o dia inteiro). */
    @Transactional(readOnly = true)
    public Map<String, Object> gerar(String companyId, String de, String ate) {
        Instant inicio = de == null || de.isBlank() ? Year.now(ZoneOffset.UTC).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant() : data(de, false);
        Instant fim = ate == null || ate.isBlank() ? Instant.now() : data(ate, true);
        if (inicio.isAfter(fim)) throw new BusinessRuleException("A data de início é posterior à data de fim.");
        // Inclui o dia de fim por inteiro — quem escreve 31/12 espera que o 31 conte.
        Instant fimInclusivo = fim.atZone(ZoneOffset.UTC).toLocalDate().atTime(23, 59, 59, 999_000_000).toInstant(ZoneOffset.UTC);

        List<PurchaseOrder> ordens = purchaseOrderRepository.findByBuyerCompanyIdAndStatusInAndCreatedAtBetweenOrderByCreatedAtAsc(
                companyId, ESTADOS_CONTAM, inicio, fimInclusivo);

        BigDecimal total = BigDecimal.ZERO, nacional = BigDecimal.ZERO, mpme = BigDecimal.ZERO;
        BigDecimal oAngolana = BigDecimal.ZERO, oImportada = BigDecimal.ZERO, oPorDeclarar = BigDecimal.ZERO;
        Map<String, Map<String, Object>> categorias = new LinkedHashMap<>();
        Map<String, Map<String, Object>> fornecedores = new LinkedHashMap<>();
        List<Map<String, Object>> anexo = new ArrayList<>();
        String moeda = ordens.isEmpty() ? "AOA" : ordens.get(0).getCurrency();

        for (PurchaseOrder po : ordens) {
            Company f = po.getSupplierCompany();
            BigDecimal valor = valorDaOrdem(po);
            boolean nac = eAngolana(f);
            total = total.add(valor);
            if (nac) nacional = nacional.add(valor);
            if (nac && f != null && MPME.contains(f.getSize())) mpme = mpme.add(valor);
            Origem o = origemDasLinhas(po);
            oAngolana = oAngolana.add(o.angolana());
            oImportada = oImportada.add(o.importada());
            oPorDeclarar = oPorDeclarar.add(o.porDeclarar());

            // Por categoria: onde há substituição possível.
            for (PurchaseOrderItem item : po.getItems()) {
                String chave = item.getProduct() == null || item.getProduct().getCategory() == null ? "Sem categoria" : item.getProduct().getCategory();
                BigDecimal linha = item.getLineTotal() == null ? BigDecimal.ZERO : item.getLineTotal();
                Map<String, Object> c = categorias.computeIfAbsent(chave, k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("categoria", k);
                    m.put("total", BigDecimal.ZERO);
                    m.put("nacional", BigDecimal.ZERO);
                    return m;
                });
                c.put("total", ((BigDecimal) c.get("total")).add(linha));
                if (nac) c.put("nacional", ((BigDecimal) c.get("nacional")).add(linha));
            }
            if (f != null) {
                Map<String, Object> atual = fornecedores.computeIfAbsent(f.getId(), k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("nome", f.getName());
                    m.put("nif", f.getTaxId());
                    m.put("pais", f.getCountry());
                    m.put("provincia", f.getProvince());
                    m.put("dimensao", f.getSize() == null ? null : f.getSize().name());
                    m.put("nacional", eAngolana(f));
                    m.put("valor", BigDecimal.ZERO);
                    m.put("ordens", 0);
                    return m;
                });
                atual.put("valor", ((BigDecimal) atual.get("valor")).add(valor));
                atual.put("ordens", (Integer) atual.get("ordens") + 1);
            }
            Map<String, Object> linhaAnexo = new LinkedHashMap<>();
            linhaAnexo.put("referencia", po.getReference());
            linhaAnexo.put("data", po.getCreatedAt());
            linhaAnexo.put("estado", po.getStatus().name());
            linhaAnexo.put("fornecedor", f == null ? null : f.getName());
            linhaAnexo.put("nif", f == null ? null : f.getTaxId());
            linhaAnexo.put("paisDoFornecedor", f == null ? null : f.getCountry());
            linhaAnexo.put("dimensao", f == null || f.getSize() == null ? null : f.getSize().name());
            linhaAnexo.put("valorSemIva", valor);
            linhaAnexo.put("origem", o.mapa());
            anexo.add(linhaAnexo);
        }

        List<Map<String, Object>> porCategoria = new ArrayList<>(categorias.values());
        porCategoria.forEach(c -> c.put("percentagemNacional", percentagem((BigDecimal) c.get("nacional"), (BigDecimal) c.get("total"))));
        porCategoria.sort(Comparator.comparing((Map<String, Object> c) -> (BigDecimal) c.get("total")).reversed());
        List<Map<String, Object>> listaFornecedores = new ArrayList<>(fornecedores.values());
        listaFornecedores.sort(Comparator.comparing((Map<String, Object> c) -> (BigDecimal) c.get("valor")).reversed());

        Company empresa = companyRepository.findById(companyId).orElse(null);
        Map<String, Object> empresaOut = new LinkedHashMap<>();
        if (empresa != null) {
            empresaOut.put("name", empresa.getName());
            empresaOut.put("taxId", empresa.getTaxId());
            empresaOut.put("province", empresa.getProvince());
        }

        double semOrigem = percentagem(oPorDeclarar, total);
        Map<String, Object> qualidade = new LinkedHashMap<>();
        qualidade.put("valorSemOrigemDeclarada", oPorDeclarar);
        qualidade.put("percentagemSemOrigem", semOrigem);
        qualidade.put("confiavel", semOrigem <= 10);
        qualidade.put("aviso", semOrigem > 10
                ? formatarPercentagem(semOrigem) + "% do valor não tem país de origem declarado nos produtos. "
                + "Enquanto assim for, a linha \"origem do bem\" está subavaliada e o relatório "
                + "não deve ser entregue como prova de conteúdo local. Peça aos fornecedores "
                + "que preencham o país de origem na ficha de cada item."
                : null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("empresa", empresa == null ? null : empresaOut);
        out.put("periodo", Map.of("de", inicio, "ate", fimInclusivo));
        out.put("moeda", moeda);
        Map<String, Object> criterio = new LinkedHashMap<>();
        criterio.put("estadosIncluidos", ESTADOS_CONTAM.stream().map(Enum::name).toList());
        criterio.put("baseDeCalculo", "Valor sem IVA. O imposto é do Estado, não da cadeia de fornecimento.");
        criterio.put("origemPorDeclarar", "Linhas sem país de origem declarado NÃO contam como angolanas.");
        out.put("criterio", criterio);
        out.put("totais", mapa("valorTotal", total, "ordens", ordens.size(), "fornecedores", fornecedores.size()));
        out.put("contratacaoNacional", mapa("valor", nacional, "percentagem", percentagem(nacional, total),
                "descricao", "Valor contratado a empresas registadas em Angola."));
        out.put("origemDoBem", mapa("angolana", oAngolana, "importada", oImportada, "porDeclarar", oPorDeclarar,
                "percentagemAngolana", percentagem(oAngolana, total),
                "descricao", "Do valor comprado, quanto corresponde a bens de origem angolana — e quanto é importação através de um intermediário local."));
        out.put("mpmeAngolana", mapa("valor", mpme, "percentagem", percentagem(mpme, total),
                "descricao", "Valor contratado a micro, pequenas e médias empresas angolanas (Lei n.º 30/11)."));
        out.put("qualidadeDosDados", qualidade);
        out.put("porCategoria", porCategoria);
        out.put("fornecedores", listaFornecedores);
        out.put("anexo", anexo);
        return out;
    }

    private static String formatarPercentagem(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static Map<String, Object> mapa(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
