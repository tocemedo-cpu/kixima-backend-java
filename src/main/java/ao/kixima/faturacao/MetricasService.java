package ao.kixima.faturacao;

import ao.kixima.conciliacao.ConciliacaoService;
import ao.kixima.conciliacao.LinhaExtratoRepository;
import ao.kixima.payment.Payment;
import ao.kixima.payment.PaymentRepository;
import ao.kixima.payment.PaymentStatus;
import ao.kixima.po.PoStatus;
import ao.kixima.po.PurchaseOrderRepository;
import ao.kixima.quote.QuoteRequestRepository;
import ao.kixima.quote.QuoteStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Espelha backend/src/services/metricasService.js — as três perguntas da
 * KIXIMA num período: quanto dinheiro passou, quantas cotações fecharam e
 * quanto tempo o dinheiro demora a ser confirmado (mediana, por canal), mais
 * a taxa de conciliação automática. Sem dados, as taxas ficam nulas em vez de
 * darem 0% — "0% de conversão" seria uma divisão por zero disfarçada.
 */
@Service
public class MetricasService {

    static final List<PoStatus> PAGAS = List.of(PoStatus.PAGA, PoStatus.EM_EXECUCAO, PoStatus.ENTREGUE,
            PoStatus.RECEBIDA_CONFORME, PoStatus.RECEBIDA_COM_DIVERGENCIA, PoStatus.CONCLUIDA);

    public record Janela(Instant de, Instant ate) {
    }

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final QuoteRequestRepository quoteRequestRepository;
    private final PaymentRepository paymentRepository;
    private final LinhaExtratoRepository linhaExtratoRepository;

    public MetricasService(PurchaseOrderRepository purchaseOrderRepository, QuoteRequestRepository quoteRequestRepository,
                           PaymentRepository paymentRepository, LinhaExtratoRepository linhaExtratoRepository) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.quoteRequestRepository = quoteRequestRepository;
        this.paymentRepository = paymentRepository;
        this.linhaExtratoRepository = linhaExtratoRepository;
    }

    public static Janela janela(int dias, Instant agora) {
        return new Janela(agora.minus(dias, ChronoUnit.DAYS), agora);
    }

    public static Double mediana(List<Double> valores) {
        if (valores == null || valores.isEmpty()) return null;
        List<Double> ord = new ArrayList<>(valores);
        ord.sort(Double::compare);
        int meio = ord.size() / 2;
        return ord.size() % 2 == 1 ? ord.get(meio) : (ord.get(meio - 1) + ord.get(meio)) / 2;
    }

    private static Double arredondar(Double h) {
        return h == null ? null : Math.round(h * 10) / 10.0;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> volumeTransacionado(Janela j) {
        List<Object[]> agg = purchaseOrderRepository.agregadoVolume(j.de(), j.ate(), PAGAS);
        long ordens = agg.isEmpty() ? 0 : ((Number) agg.get(0)[0]).longValue();
        BigDecimal soma = agg.isEmpty() || agg.get(0)[1] == null ? BigDecimal.ZERO : (BigDecimal) agg.get(0)[1];
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ordens", ordens);
        m.put("total", soma.doubleValue());
        m.put("ticketMedio", ordens > 0 ? soma.doubleValue() / ordens : 0d);
        return m;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> conversaoDeCotacoes(Janela j) {
        long pedidas = quoteRequestRepository.countByCreatedAtBetween(j.de(), j.ate());
        long respondidas = quoteRequestRepository.countByCreatedAtBetweenAndStatusIn(j.de(), j.ate(), List.of(QuoteStatus.RESPONDIDA, QuoteStatus.FECHADA));
        long fechadas = quoteRequestRepository.countByCreatedAtBetweenAndStatusIn(j.de(), j.ate(), List.of(QuoteStatus.FECHADA));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pedidas", pedidas);
        m.put("respondidas", respondidas);
        m.put("fechadas", fechadas);
        m.put("taxaDeResposta", pedidas == 0 ? null : Math.round(respondidas * 1000.0 / pedidas) / 10.0);
        m.put("taxaDeFecho", pedidas == 0 ? null : Math.round(fechadas * 1000.0 / pedidas) / 10.0);
        return m;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> tempoAteConfirmacao(Janela j) {
        List<Payment> pagamentos = paymentRepository.findProcessadosNoPeriodo(j.de(), j.ate(), PaymentStatus.PROCESSADO, PageRequest.of(0, 20000));
        Map<String, List<Double>> horasPorCanal = new LinkedHashMap<>();
        for (Payment p : pagamentos) {
            if (p.getInvoice() == null || p.getInvoice().getIssuedAt() == null || p.getProcessedAt() == null) continue;
            double horas = (p.getProcessedAt().toEpochMilli() - p.getInvoice().getIssuedAt().toEpochMilli()) / 3600000.0;
            if (horas < 0) continue; // dados incoerentes não entram na conta
            String canal = p.getCanal() == null ? "null" : p.getCanal().name();
            horasPorCanal.computeIfAbsent(canal, k -> new ArrayList<>()).add(horas);
        }
        List<Double> todas = new ArrayList<>();
        horasPorCanal.values().forEach(todas::addAll);
        Map<String, Object> porCanal = new LinkedHashMap<>();
        for (Map.Entry<String, List<Double>> e : horasPorCanal.entrySet()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("pagamentos", e.getValue().size());
            c.put("medianaHoras", arredondar(mediana(e.getValue())));
            porCanal.put(e.getKey(), c);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pagamentos", todas.size());
        m.put("medianaHoras", arredondar(mediana(todas)));
        m.put("porCanal", porCanal);
        return m;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> conciliacaoAutomatica(Janela j) {
        long conciliadas = linhaExtratoRepository.countByImportadaEmBetweenAndEstado(j.de(), j.ate(), ConciliacaoService.CONCILIADA);
        long porResolver = linhaExtratoRepository.countByImportadaEmBetweenAndEstadoIn(j.de(), j.ate(),
                List.of(ConciliacaoService.SEM_CORRESPONDENCIA, ConciliacaoService.DIVERGENTE));
        long total = conciliadas + porResolver;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("linhas", total);
        m.put("conciliadas", conciliadas);
        m.put("porResolver", porResolver);
        m.put("taxaAutomatica", total == 0 ? null : Math.round(conciliadas * 1000.0 / total) / 10.0);
        return m;
    }

    /** `dias` vem da query string: qualquer coisa que não seja um inteiro positivo cai nos 30 por omissão. */
    @Transactional(readOnly = true)
    public Map<String, Object> resumo(String dias) {
        int diasEfetivos = 30;
        if (dias != null && !dias.isBlank()) {
            try {
                int pedido = (int) Math.floor(Double.parseDouble(dias.trim()));
                if (pedido > 0) diasEfetivos = pedido;
            } catch (NumberFormatException ignored) {
                // "abc" → 30
            }
        }
        Janela periodo = janela(diasEfetivos, Instant.now());
        Map<String, Object> periodoOut = new LinkedHashMap<>();
        periodoOut.put("de", periodo.de());
        periodoOut.put("ate", periodo.ate());
        periodoOut.put("dias", diasEfetivos);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("periodo", periodoOut);
        out.put("volume", volumeTransacionado(periodo));
        out.put("cotacoes", conversaoDeCotacoes(periodo));
        out.put("tempoAteConfirmacao", tempoAteConfirmacao(periodo));
        out.put("conciliacao", conciliacaoAutomatica(periodo));
        return out;
    }
}
