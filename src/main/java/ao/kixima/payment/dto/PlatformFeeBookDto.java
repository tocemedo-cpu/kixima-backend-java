package ao.kixima.payment.dto;

import ao.kixima.common.Decimais;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;
import java.util.List;

/** Espelha adminService.listPlatformFees — o livro de taxas da plataforma, todas as empresas. */
public record PlatformFeeBookDto(List<PlatformFeeDto> fees, Kpis kpis) {

    public record Kpis(int total, @JsonSerialize(using = Decimais.ComoNumeroJs.class) BigDecimal totalAOA, @JsonSerialize(using = Decimais.ComoNumeroJs.class) BigDecimal pendingAOA, int cobradas) {
    }
}
