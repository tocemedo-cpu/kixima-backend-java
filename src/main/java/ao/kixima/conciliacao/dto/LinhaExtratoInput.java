package ao.kixima.conciliacao.dto;

import java.math.BigDecimal;

/** Uma linha bruta do extrato, tal como o banco (ou o ficheiro exportado) a dá. */
public record LinhaExtratoInput(String idNoBanco, String dataValor, BigDecimal montante, String moeda, String descricao,
                                String referencia) {
}
