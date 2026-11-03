package ao.kixima.marketplace;

import ao.kixima.testsupport.Contas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regressão dos HTTP 500 nos endpoints públicos (search, facets, suppliers, stats) e no registo.
 *
 * <p>Os caminhos normais têm de responder 2xx sobre uma base com o schema actual e dados; entradas extremas têm de
 * ser recusadas com 422 (ou limitadas), nunca 500 nem o 409 «Já existe um registo» que escondia um erro de SQL.
 * A causa de 500 que NÃO vem do código — utilizador da base sem privilégios nas tabelas — reproduz-se fora da suite
 * (ver o relatório da investigação); aqui garante-se que o código não é a origem.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PublicEntradasInvalidasTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void osQuatroEndpointsPublicosRespondem200SemSessao() throws Exception {
        for (String url : new String[]{"/api/public/catalog/search", "/api/public/catalog/facets", "/api/public/catalog/suppliers",
                "/api/public/stats"}) {
            mockMvc.perform(get(url)).andExpect(status().isOk());
        }
    }

    @Test
    void precoInfinitoOuForaDeEscalaEhRecusadoCom422() throws Exception {
        // Double.parseDouble("1e999") = Infinity → BigDecimal.valueOf rebentava com 500.
        for (String param : new String[]{"minPrice=1e999", "maxPrice=1e999", "minPrice=Infinity", "maxPrice=-Infinity"}) {
            mockMvc.perform(get("/api/public/catalog/search?" + param)).andExpect(status().isUnprocessableEntity());
            mockMvc.perform(get("/api/public/catalog/facets?" + param)).andExpect(status().isUnprocessableEntity());
        }
        // Valores grandes mas finitos continuam a ser aceites.
        mockMvc.perform(get("/api/public/catalog/search?minPrice=9999999999999999999999999")).andExpect(status().isOk());
    }

    @Test
    void paginaEnormeNaoTransbordaOOffset() throws Exception {
        // (page - 1) * limit em int dava OFFSET negativo → erro do PostgreSQL → 409 enganador.
        for (String page : new String[]{"2147483647", "3000000000", "99999999999999", "44739244"}) {
            JsonNode r = objectMapper.readTree(mockMvc.perform(get("/api/public/catalog/search?limit=48&page=" + page))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(r.get("items")).isEmpty();
            assertThat(r.get("page").asInt()).isLessThanOrEqualTo(1_000_000);
        }
    }

    @Test
    void caracterNuloNoTextoEhRecusadoCom422() throws Exception {
        // O PostgreSQL recusa NUL em texto; chegava à base e voltava como 409 «Já existe um registo».
        // .param(): o MockMvc recodificaria um «%00» escrito no URL como texto literal, sem NUL.
        mockMvc.perform(get("/api/public/catalog/search").param("q", "a\u0000b")).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/public/catalog/search").param("category", "\u0000")).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/public/catalog/facets").param("country", "a\u0000")).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/public/catalog/facets").param("city", "\u0000x")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void registoValidoDa201EDuplicadoDa409SemRebentar() throws Exception {
        String email = Contas.emailUnico("registo-regressao");
        String corpo = objectMapper.writeValueAsString(Map.of("type", "FORNECEDOR", "name", "Pessoa de Teste", "email", email,
                "password", Contas.senhaNova(), "termsAccepted", true));
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.emailVerificationRequired").value(true));
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isConflict());
    }

    @Test
    void registoComDadosInvalidosNuncaDa500() throws Exception {
        String senha = Contas.senhaNova();
        String[] corpos = {
                objectMapper.writeValueAsString(Map.of("type", "OUTRO", "name", "Pessoa", "email", Contas.emailUnico("r1"), "password", senha, "termsAccepted", true)),
                objectMapper.writeValueAsString(Map.of("type", "CLIENTE", "name", "Pessoa", "email", Contas.emailUnico("r2"), "password", senha)),
                objectMapper.writeValueAsString(Map.of("type", "CLIENTE", "name", "Pessoa", "email", "nao-e-email", "password", senha, "termsAccepted", true)),
                objectMapper.writeValueAsString(Map.of("type", "CLIENTE", "name", "Pessoa", "email", Contas.emailUnico("r3"), "password", "curta", "termsAccepted", true)),
                objectMapper.writeValueAsString(Map.of("type", "CLIENTE", "name", "Pe\u0000ssoa", "email", Contas.emailUnico("r4"), "password", senha, "termsAccepted", true)),
                "{}", "[]", "nao e json",
        };
        for (String corpo : corpos) {
            int estado = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(corpo))
                    .andReturn().getResponse().getStatus();
            assertThat(estado).as("corpo %s", corpo).isBetween(400, 499);
        }
    }
}
