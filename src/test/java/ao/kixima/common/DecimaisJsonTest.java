package ao.kixima.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato dos decimais (ver JacksonDecimalConfig): as colunas Decimal saem
 * como texto sem zeros à direita, exactamente como o `toJSON()` do decimal.js
 * que o Prisma usa — foi a maior fonte de diferenças no replay do M7.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DecimaisJsonTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void textoComoODecimalJs() {
        assertThat(Decimais.texto(new BigDecimal("273600.00"))).isEqualTo("273600");
        assertThat(Decimais.texto(new BigDecimal("12.50"))).isEqualTo("12.5");
        assertThat(Decimais.texto(new BigDecimal("0.00"))).isEqualTo("0");
        assertThat(Decimais.texto(new BigDecimal("1E+3"))).isEqualTo("1000");
        assertThat(Decimais.texto(new BigDecimal("-0.065"))).isEqualTo("-0.065");
        assertThat(Decimais.texto(null)).isNull();
    }

    @Test
    void oObjectMapperDaAplicacaoEscreveBigDecimalComoTexto() throws Exception {
        assertThat(objectMapper.writeValueAsString(Map.of("v", new BigDecimal("3200000.00")))).isEqualTo("{\"v\":\"3200000\"}");
        // O que é aritmética em JS continua número.
        assertThat(objectMapper.writeValueAsString(Map.of("v", 4.7))).isEqualTo("{\"v\":4.7}");
    }

    @Test
    void oCatalogoDevolveUnitPriceComoTextoComoONode() throws Exception {
        var login = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"email\":\"comprador@petroangola.co.ao\",\"password\":\"Kixima@123\"}"))
                .andExpect(status().isOk()).andReturn();
        String token = objectMapper.readTree(login.getResponse().getContentAsString()).get("token").asText();
        mockMvc.perform(get("/api/catalog").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].unitPrice").isString())
                .andExpect(jsonPath("$[0].rating").isNumber());
    }
}
