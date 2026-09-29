package ao.kixima.common;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Os dois modos em que um decimal sai no JSON do Node:
 * <ul>
 *   <li>coluna Decimal do Prisma → texto ({@code "273600"}), o {@code toJSON()} do decimal.js —
 *       é o que {@link #texto} produz e o que JacksonDecimalConfig aplica a todo o {@link BigDecimal};</li>
 *   <li>aritmética em JS ({@code Number(d)}, somas, {@code Math.round}) → número ({@code 273600}) —
 *       é {@link #numero} para valores em Map e {@link ComoNumeroJs} para componentes de record.</li>
 * </ul>
 */
public final class Decimais {

    private Decimais() {
    }

    public static String texto(BigDecimal v) {
        if (v == null) return null;
        BigDecimal limpo = v.stripTrailingZeros();
        return limpo.scale() < 0 ? limpo.setScale(0).toPlainString() : limpo.toPlainString();
    }

    /** `Number(d ?? 0)`: um número JSON com o texto do decimal.js. */
    public static Number numero(BigDecimal v) {
        return new NumeroJs(v == null ? BigDecimal.ZERO : v);
    }

    /** O mesmo, em profundidade, para respostas construídas como Map/List em que TODOS os decimais são aritmética JS. */
    @SuppressWarnings("unchecked")
    public static Object numerosEmProfundidade(Object valor) {
        if (valor instanceof BigDecimal bd) return numero(bd);
        if (valor instanceof Map<?, ?> m) {
            Map<Object, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(e.getKey(), numerosEmProfundidade(e.getValue()));
            return out;
        }
        if (valor instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object o : l) out.add(numerosEmProfundidade(o));
            return out;
        }
        return valor;
    }

    /** Jackson escreve um Number desconhecido com {@code writeNumber(toString())} — um token numérico com o texto exacto do JS. */
    public static final class NumeroJs extends Number {
        private final BigDecimal valor;

        NumeroJs(BigDecimal valor) {
            this.valor = valor;
        }

        @Override
        public int intValue() {
            return valor.intValue();
        }

        @Override
        public long longValue() {
            return valor.longValue();
        }

        @Override
        public float floatValue() {
            return valor.floatValue();
        }

        @Override
        public double doubleValue() {
            return valor.doubleValue();
        }

        @Override
        public String toString() {
            return texto(valor);
        }
    }

    /** Para componentes BigDecimal de records que no Node são números (preços de tabela, KPIs somados). */
    public static final class ComoNumeroJs extends JsonSerializer<BigDecimal> {
        @Override
        public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            if (value == null) gen.writeNull();
            else gen.writeNumber(texto(value));
        }
    }
}
