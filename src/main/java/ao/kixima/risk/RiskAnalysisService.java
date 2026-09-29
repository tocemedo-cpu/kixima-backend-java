package ao.kixima.risk;

import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Espelha backend/src/services/riskAnalysisService.js — Trust & Safety do
 * Chat Comercial: deteção de tentativas de levar a negociação/pagamento
 * para fora da plataforma.
 *
 * NÃO é uma lista de palavras proibidas — cada mensagem é pontuada por
 * VÁRIOS sinais independentes; só a COMBINAÇÃO (ou um sinal muito
 * explícito) ultrapassa o limiar que gera alerta. Heurística, não
 * infalível — pensada para reduzir falsos positivos, não eliminá-los (o
 * Suporte pode sempre reclassificar como falso positivo, ver
 * RiskAlertService). NÃO BLOQUEIA nada — só classifica; LOW nunca é
 * persistido.
 */
@Service
public class RiskAnalysisService {

    public record Sinal(String key, String label, int peso, Pattern teste) {
    }

    private static final List<Sinal> SINAIS = List.of(
            new Sinal("telefone", "partilha de número de telefone", 2,
                    Pattern.compile("(\\+?244[\\s.-]?)?\\b9\\d{2}[\\s.-]?\\d{3}[\\s.-]?\\d{3}\\b")),
            new Sinal("email", "partilha de email", 2,
                    Pattern.compile("\\b[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}\\b")),
            new Sinal("app_mensagens", "referência a um aplicativo de mensagens externo", 2,
                    Pattern.compile("\\b(whatsapp|zap ?zap|telegram|signal|viber|messenger|imo)\\b")),
            new Sinal("pagamento_fora", "linguagem de pagamento fora da plataforma", 4,
                    Pattern.compile("(fora da kixima|fora da plataforma|sem passar pela kixima|sem passar pela plataforma|"
                            + "pagamento direto|pagar direto|transferencia direta|deposito direto|evitar a taxa|"
                            + "sem taxa da kixima|poupar a taxa|por fora\\b)")),
            new Sinal("cancelar_continuar_fora", "indício de cancelar na Kixima para continuar fora", 5,
                    Pattern.compile("(cancelar(mos)? (aqui|a compra|o pedido|na kixima)|cancela(mos)? (aqui|isso) e "
                            + "(continuamos|fechamos|seguimos))")),
            new Sinal("negocio_a_parte", "intenção de fechar contrato/negócio à parte", 5,
                    Pattern.compile("(contrato a parte|negocio a parte|combinamos por fora|fechamos (isso |o negocio )?"
                            + "fora|acordo a parte)")));

    private static final List<RiskLevel> LEVELS = List.of(RiskLevel.LOW, RiskLevel.MEDIUM, RiskLevel.HIGH, RiskLevel.CRITICAL);

    private static String semAcentos(String s) {
        if (s == null) return "";
        String normalizado = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return normalizado.toLowerCase();
    }

    private static RiskLevel limiarPara(int score) {
        if (score >= 11) return RiskLevel.CRITICAL;
        if (score >= 7) return RiskLevel.HIGH;
        if (score >= 4) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }

    public record Resultado(RiskLevel level, int score, String reason, List<String> signals) {
    }

    /**
     * Analisa UMA mensagem, com o contexto do que já se passou na
     * conversa. {@code escalar}=true quando já existe um alerta MEDIUM+
     * em aberto nesta conversa — um segundo indício depois de já ter
     * havido um alerta pesa mais do que o primeiro.
     */
    public Resultado analisar(String texto, boolean escalar) {
        String normalizado = semAcentos(texto);
        List<Sinal> encontrados = new ArrayList<>();
        int score = 0;
        for (Sinal s : SINAIS) {
            if (s.teste().matcher(normalizado).find()) {
                encontrados.add(s);
                score += s.peso();
            }
        }

        RiskLevel nivel = limiarPara(score);
        boolean escalado = escalar && score >= 4 && LEVELS.indexOf(nivel) < LEVELS.size() - 1;
        if (escalado) nivel = LEVELS.get(LEVELS.indexOf(nivel) + 1);

        String reason = encontrados.isEmpty()
                ? "Nenhum sinal de risco detetado."
                : "Sinais detetados: " + String.join("; ", encontrados.stream().map(Sinal::label).toList()) + "."
                + (escalado ? " Repetição de indício após alerta já aberto nesta conversa." : "");

        return new Resultado(nivel, score, reason, encontrados.stream().map(Sinal::key).toList());
    }
}
