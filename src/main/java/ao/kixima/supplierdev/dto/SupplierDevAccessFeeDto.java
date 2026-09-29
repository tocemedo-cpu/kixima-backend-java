package ao.kixima.supplierdev.dto;

import ao.kixima.common.Decimais;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;

/** Espelha o `accessFee` do retorno de supplierDevService.create — inclui `status`, ao contrário de GET /fee. */
public record SupplierDevAccessFeeDto(@JsonSerialize(using = Decimais.ComoNumeroJs.class) BigDecimal amountUsd, String currency, boolean dueOnSubmission, String status,
                                       boolean remainderCustom) {
}
