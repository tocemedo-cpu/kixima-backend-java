package ao.kixima.config;

import ao.kixima.common.Decimais;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * Contrato dos decimais: o Node devolve TODAS as colunas `Decimal` do Prisma
 * como texto ({@code "unitPrice": "3200000"}, {@code "totalAmount": "273600"} —
 * o {@code toJSON()} do decimal.js, sem zeros à direita), e o frontend lê-as
 * com {@code Number(...)}. O replay de contrato do M7 contou 228 campos assim
 * num cenário de 81 pedidos. Um {@link BigDecimal} sai por isso sempre como
 * texto, com a mesma normalização; o que no Node é aritmética em JS
 * (métricas, médias, contagens) está em Java como double/long e continua a
 * sair como número.
 */
@Configuration
public class JacksonDecimalConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer decimaisComoNoPrisma() {
        SimpleModule modulo = new SimpleModule("decimais-como-no-prisma");
        modulo.addSerializer(BigDecimal.class, new JsonSerializer<>() {
            @Override
            public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeString(Decimais.texto(value));
            }
        });
        return builder -> builder.modulesToInstall(modulo);
    }
}
