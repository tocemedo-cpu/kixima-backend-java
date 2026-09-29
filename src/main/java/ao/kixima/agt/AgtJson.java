package ao.kixima.agt;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serialização JSON compacta para os payloads assinados da AGT — tem de
 * produzir bytes IDÊNTICOS ao {@code JSON.stringify} do Node (a assinatura
 * JWS é sobre esses bytes, byte a byte, ver PLANO.md secção 3, M4c/M4d).
 *
 * Dois pontos onde um {@code ObjectMapper} por omissão divergiria:
 * <ol>
 *   <li>Ordem dos campos — o Node depende da ordem de inserção do objeto
 *   literal (comentário em agtSigningService.js). Aqui constrói-se sempre
 *   com {@link LinkedHashMap} explícito, nunca um record/POJO cuja ordem de
 *   serialização não é garantida — a mesma disciplina, só que pela via
 *   Java.</li>
 *   <li>Números — {@code BigDecimal} por omissão serializa com o
 *   {@code scale} exacto (ex.: "273600.00"); o {@code Number} do JS nunca
 *   tem zeros à direita (ex.: "273600"). {@link BigDecimalSemZeros} imprime
 *   {@code stripTrailingZeros().toPlainString()} — replica exactamente o
 *   que {@code JSON.stringify} produz para os valores monetários (2 casas)
 *   usados nestes payloads.</li>
 * </ol>
 */
final class AgtJson {

    static final ObjectMapper MAPPER = build();

    private AgtJson() {
    }

    private static ObjectMapper build() {
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule modulo = new SimpleModule();
        modulo.addSerializer(BigDecimal.class, new BigDecimalSemZeros());
        mapper.registerModule(modulo);
        return mapper;
    }

    /** Constrói um mapa ordenado — açúcar para não repetir {@code new LinkedHashMap<>()} + `put` em cada chamador. */
    static Map<String, Object> mapa(Object... paresChaveValor) {
        if (paresChaveValor.length % 2 != 0) {
            throw new IllegalArgumentException("Número ímpar de argumentos — esperado pares chave/valor.");
        }
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < paresChaveValor.length; i += 2) {
            m.put((String) paresChaveValor[i], paresChaveValor[i + 1]);
        }
        return m;
    }

    static String stringify(Object valor) {
        try {
            return MAPPER.writeValueAsString(valor);
        } catch (Exception e) {
            throw new IllegalStateException("Falha a serializar payload AGT para JSON.", e);
        }
    }

    private static final class BigDecimalSemZeros extends StdSerializer<BigDecimal> {
        BigDecimalSemZeros() {
            super(BigDecimal.class);
        }

        @Override
        public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            BigDecimal semZeros = value.stripTrailingZeros();
            // stripTrailingZeros() de um valor exactamente zero pode devolver scale
            // negativo consoante a versão do JDK — normaliza sempre para "0" simples,
            // tal como o JS nunca escreve "0.0"/"0E+1".
            String texto = semZeros.compareTo(BigDecimal.ZERO) == 0 ? "0" : semZeros.toPlainString();
            gen.writeRawValue(texto);
        }
    }
}
