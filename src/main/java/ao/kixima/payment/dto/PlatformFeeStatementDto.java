package ao.kixima.payment.dto;

import ao.kixima.common.Decimais;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Espelha platformFeeService.statementFor — extrato de taxas de UMA empresa
 * (fornecedor): lista completa + totais + a fórmula. Nomes dos KPIs mantidos
 * por compatibilidade da UI ({@code totalAOA} etc.); os valores estão em USD.
 */
public record PlatformFeeStatementDto(CompanyRef company, List<PlatformFeeDto> fees, Kpis kpis, Formula formula, Instant generatedAt) {

    public record CompanyRef(String id, String name, String taxId, String address, String city, String province, String country,
                             String contactEmail, String plan, String size, BigDecimal seatPriceUsd) {
    }

    public record Kpis(int total, @JsonSerialize(using = Decimais.ComoNumeroJs.class) BigDecimal totalAOA, @JsonSerialize(using = Decimais.ComoNumeroJs.class) BigDecimal pendingAOA, @JsonSerialize(using = Decimais.ComoNumeroJs.class) BigDecimal chargedAOA, int pendentes, int cobradas,
                       String currency) {
    }

    public record Formula(@JsonSerialize(using = Decimais.ComoNumeroJs.class) BigDecimal perPo, @JsonSerialize(using = Decimais.ComoNumeroJs.class) BigDecimal perInvoice, @JsonSerialize(using = Decimais.ComoNumeroJs.class) BigDecimal thresholdUsd, @JsonSerialize(using = Decimais.ComoNumeroJs.class) BigDecimal percentAbove, String currency) {
    }
}
