package ao.kixima.porobo.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Um passo da justificação de uma previsão/proposta (contrato {@code PassoCalculo} do frontend), em texto para mostrar. */
public record PoRoboPassoCalculo(String rotulo, @JsonInclude(JsonInclude.Include.NON_NULL) String formula, String valor) {
}
