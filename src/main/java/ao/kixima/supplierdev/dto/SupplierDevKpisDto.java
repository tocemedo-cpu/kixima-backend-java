package ao.kixima.supplierdev.dto;

import ao.kixima.common.Decimais;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;

public record SupplierDevKpisDto(long total, long recebidas, long emAnalise, long acompanhamento, long concluidas,
                                  long taxasPendentes, @JsonSerialize(using = Decimais.ComoNumeroJs.class) BigDecimal taxasPendentesUsd) {
}
