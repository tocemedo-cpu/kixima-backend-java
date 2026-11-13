package ao.kixima.porobo;

import ao.kixima.porobo.dto.PoRoboPassoCalculo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/** (De)serialização das listas guardadas em texto JSON (justificação e erros). Falha a ler nunca parte uma listagem. */
final class PoRoboJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PoRoboJson() {
    }

    static String escrever(Object valor) {
        try {
            return MAPPER.writeValueAsString(valor);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Não foi possível serializar o JSON do PO Robot.", e);
        }
    }

    static List<PoRoboPassoCalculo> lerPassos(String json) {
        try {
            return json == null || json.isBlank() ? List.of() : MAPPER.readValue(json, new TypeReference<List<PoRoboPassoCalculo>>() {
            });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    static List<String> lerTextos(String json) {
        try {
            return json == null || json.isBlank() ? List.of() : MAPPER.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
