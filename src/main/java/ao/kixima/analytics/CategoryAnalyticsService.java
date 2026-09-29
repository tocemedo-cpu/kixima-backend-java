package ao.kixima.analytics;

import ao.kixima.po.PoStatus;
import ao.kixima.po.PurchaseOrderItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Espelha só a parte de backend/src/services/categoryAnalyticsService.js que
 * o PO Robot reutiliza — {@code historicoMensalPorProduto} e
 * {@code mediaMensalPorProduto} (a MESMA função do Category Management,
 * nunca duplicada). Lê exclusivamente o que já existe (PurchaseOrderItem →
 * PurchaseOrder), só POs de compra reconhecida (PAGA em diante).
 *
 * NÃO PORTADO: volumePorCategoria/previsaoNecessidade/oportunidades de
 * consolidação — o dashboard de Category Management (ver
 * DiscountThresholdService, javadoc) fica para quando esse troço for portado.
 */
@Service
public class CategoryAnalyticsService {

    static final List<PoStatus> RECONHECIDAS = List.of(PoStatus.PAGA, PoStatus.EM_EXECUCAO, PoStatus.ENTREGUE,
            PoStatus.RECEBIDA_CONFORME, PoStatus.RECEBIDA_COM_DIVERGENCIA, PoStatus.CONCLUIDA);

    public record MesQuantidade(String mes, long quantidade) {
    }

    /** Espelha o objecto devolvido por mediaMensalPorProduto. */
    public record MediaMensal(String produtoId, int amostras, int mesesComCompra, Integer mesesDecorridos,
                              Long quantidadeTotal, BigDecimal mediaMensal) {
    }

    private final PurchaseOrderItemRepository purchaseOrderItemRepository;

    public CategoryAnalyticsService(PurchaseOrderItemRepository purchaseOrderItemRepository) {
        this.purchaseOrderItemRepository = purchaseOrderItemRepository;
    }

    /** Quantidade comprada de um produto, agrupada por mês (YYYY-MM), ordenada. */
    @Transactional(readOnly = true)
    public List<MesQuantidade> historicoMensalPorProduto(String companyId, String productId, int meses) {
        Instant ate = Instant.now();
        Instant de = ate.minus((long) meses * 30, ChronoUnit.DAYS);

        TreeMap<String, Long> porMes = new TreeMap<>();
        for (Object[] linha : purchaseOrderItemRepository.historicoDeCompra(companyId, productId, RECONHECIDAS, de, ate)) {
            YearMonth ym = YearMonth.from(((Instant) linha[0]).atZone(ZoneOffset.UTC));
            porMes.merge(ym.toString(), ((Number) linha[1]).longValue(), Long::sum);
        }

        List<MesQuantidade> historico = new ArrayList<>();
        porMes.forEach((mes, quantidade) -> historico.add(new MesQuantidade(mes, quantidade)));
        return historico;
    }

    /**
     * Média mensal de compra de um produto. Divide-se pelos MESES DECORRIDOS
     * desde a primeira compra, não só pelos meses em que houve compra — um
     * produto comprado a cada 3 meses tem média mensal de 1/3 da quantidade
     * por compra, senão o robot pediria a quantidade inteira todos os meses.
     */
    @Transactional(readOnly = true)
    public MediaMensal mediaMensalPorProduto(String companyId, String productId) {
        List<MesQuantidade> historico = historicoMensalPorProduto(companyId, productId, 12);
        if (historico.isEmpty()) {
            return new MediaMensal(productId, 0, 0, null, null, BigDecimal.ZERO);
        }

        long quantidadeTotal = historico.stream().mapToLong(MesQuantidade::quantidade).sum();
        YearMonth primeiro = YearMonth.parse(historico.get(0).mes());
        YearMonth agora = YearMonth.now(ZoneOffset.UTC);
        int mesesDecorridos = (int) Math.max(1, ChronoUnit.MONTHS.between(primeiro, agora) + 1);

        BigDecimal media = BigDecimal.valueOf(quantidadeTotal)
                .divide(BigDecimal.valueOf(mesesDecorridos), 2, RoundingMode.HALF_UP);
        return new MediaMensal(productId, historico.size(), historico.size(), mesesDecorridos, quantidadeTotal, media);
    }

    // --- Category Management (Lacunas D.7) ------------------------------------------

    public record Janela(Instant de, Instant ate) {
    }

    public static Janela janela(int dias) {
        Instant agora = Instant.now();
        return new Janela(agora.minus(dias, ChronoUnit.DAYS), agora);
    }

    private static double num(Object v) {
        return v == null ? 0 : ((Number) v).doubleValue();
    }

    /** Volume de compra reconhecida por categoria de produto, num período (365 dias por omissão). */
    @Transactional(readOnly = true)
    public Map<String, Object> volumePorCategoria(String companyId, Janela periodo) {
        Janela j = periodo != null ? periodo : janela(365);
        Map<String, Double> porCategoria = new LinkedHashMap<>();
        double total = 0;
        for (Object[] it : purchaseOrderItemRepository.linhasReconhecidasDaEmpresa(companyId, RECONHECIDAS, j.de(), j.ate())) {
            String categoria = it[0] == null ? "Sem categoria" : (String) it[0];
            double valor = num(it[1]);
            porCategoria.merge(categoria, valor, Double::sum);
            total += valor;
        }
        final double totalFinal = total;
        List<Map<String, Object>> categorias = new ArrayList<>();
        porCategoria.forEach((categoria, valor) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("categoria", categoria);
            m.put("valor", valor);
            m.put("percentual", totalFinal > 0 ? Math.round((valor / totalFinal) * 1000) / 10.0 : 0d);
            categorias.add(m);
        });
        categorias.sort((a, b) -> Double.compare((double) b.get("valor"), (double) a.get("valor")));
        Map<String, Object> periodoOut = new LinkedHashMap<>();
        periodoOut.put("de", j.de());
        periodoOut.put("ate", j.ate());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("periodo", periodoOut);
        out.put("total", total);
        out.put("categorias", categorias);
        return out;
    }

    /** Previsão simples (média móvel dos últimos `janelaMeses` com compra) — é uma média, não um modelo, e diz-se isso ao cliente. */
    @Transactional(readOnly = true)
    public Map<String, Object> previsaoNecessidade(String companyId, String productId, int janelaMeses) {
        List<MesQuantidade> historico = historicoMensalPorProduto(companyId, productId, 12);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("produtoId", productId);
        if (historico.isEmpty()) {
            out.put("previsaoProximoMes", 0d);
            out.put("baseMeses", 0);
            return out;
        }
        List<MesQuantidade> ultimos = historico.subList(Math.max(0, historico.size() - janelaMeses), historico.size());
        double media = ultimos.stream().mapToLong(MesQuantidade::quantidade).sum() / (double) ultimos.size();
        out.put("previsaoProximoMes", Math.round(media * 100) / 100.0);
        out.put("baseMeses", ultimos.size());
        return out;
    }

    /** Um patamar de desconto como oportunidadesConsolidacao o recebe (vem de quem chama, nunca daqui). */
    public record Patamar(double minVolumeUsd, double discountPercent, boolean ativo) {
    }

    /** Categorias com compra fragmentada (≥ 3 POs) que, consolidadas, cruzariam o próximo patamar configurado. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> oportunidadesConsolidacao(String companyId, Janela periodo, List<Patamar> thresholds) {
        List<Patamar> patamares = new ArrayList<>();
        if (thresholds != null) for (Patamar t : thresholds) if (t.ativo()) patamares.add(t);
        patamares.sort(java.util.Comparator.comparingDouble(Patamar::minVolumeUsd));
        if (patamares.isEmpty()) return List.of();

        Janela j = periodo != null ? periodo : janela(365);
        Map<String, double[]> totais = new LinkedHashMap<>();
        Map<String, Set<String>> pos = new LinkedHashMap<>();
        for (Object[] it : purchaseOrderItemRepository.linhasReconhecidasDaEmpresa(companyId, RECONHECIDAS, j.de(), j.ate())) {
            String categoria = it[0] == null ? "Sem categoria" : (String) it[0];
            totais.computeIfAbsent(categoria, k -> new double[1])[0] += num(it[1]);
            pos.computeIfAbsent(categoria, k -> new HashSet<>()).add((String) it[2]);
        }
        List<Map<String, Object>> oportunidades = new ArrayList<>();
        for (Map.Entry<String, double[]> e : totais.entrySet()) {
            double total = e.getValue()[0];
            int numeroPos = pos.get(e.getKey()).size();
            if (numeroPos < 3) continue;
            Patamar proximo = patamares.stream().filter(t -> t.minVolumeUsd() > total).findFirst().orElse(null);
            if (proximo == null) continue; // já está no maior patamar ativo
            double faltamUsd = proximo.minVolumeUsd() - total;
            double mediaPorPo = total / numeroPos;
            if (faltamUsd > 0 && faltamUsd <= mediaPorPo) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("categoria", e.getKey());
                m.put("volumeAtual", total);
                m.put("numeroPos", numeroPos);
                m.put("proximoThresholdUsd", proximo.minVolumeUsd());
                m.put("descontoPotencial", proximo.discountPercent());
                m.put("faltamUsd", Math.round(faltamUsd * 100) / 100.0);
                oportunidades.add(m);
            }
        }
        oportunidades.sort((a, b) -> Double.compare((double) a.get("faltamUsd"), (double) b.get("faltamUsd")));
        return oportunidades;
    }

    /** Espelha o objecto de mediaMensalPorProduto tal como a rota o devolve (mesmas chaves, incluindo as ausentes sem histórico). */
    public static Map<String, Object> mediaMensalOut(MediaMensal m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("produtoId", m.produtoId());
        out.put("amostras", m.amostras());
        out.put("mesesComCompra", m.mesesComCompra());
        if (m.mesesDecorridos() != null) out.put("mesesDecorridos", m.mesesDecorridos());
        if (m.quantidadeTotal() != null) out.put("quantidadeTotal", m.quantidadeTotal());
        out.put("mediaMensal", m.mediaMensal());
        return out;
    }
}
