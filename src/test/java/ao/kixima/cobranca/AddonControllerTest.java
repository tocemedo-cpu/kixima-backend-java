package ao.kixima.cobranca;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Espelha a parte de cobrança de tests/po-robot-addon.test.js: pedir → comprovativo → confirmar → add-on ativo, com validade. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AddonControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String COMPANY_ADMIN_EMAIL = "admin@petroangola.co.ao";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";
    private static final String FINANCEIRO_EMAIL = "financeiro@petroangola.co.ao";
    private static final String ADMIN_SISTEMA_EMAIL = "admin@kixima.co.ao";
    private static final String ADDON_KEY = "PO_ROBOT";
    private static final byte[] COMPROVATIVO = "%PDF-1.4 transferencia add-on".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    private String login(String email) throws Exception {
        var res = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    private String companyId() {
        return jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = 'AO-CLI-0001'", String.class);
    }

    private void porNoPlano(String plano, int rank) {
        jdbcTemplate.update("UPDATE companies SET plan = ?::\"CompanyPlan\", search_rank = ? WHERE id = ?", plano, rank, companyId());
        entityManager.clear();
    }

    private JsonNode pedir(String token, int esperado) throws Exception {
        var res = mockMvc.perform(post("/api/addons/" + ADDON_KEY + "/pedir").header("Authorization", "Bearer " + token))
                .andExpect(status().is(esperado)).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    private void comprovativo(String token, String id) throws Exception {
        mockMvc.perform(multipart("/api/addons/" + id + "/comprovativo")
                        .file(new MockMultipartFile("comprovativo", "transferencia.pdf", "application/pdf", COMPROVATIVO))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPROVATIVO_ENVIADO"))
                .andExpect(jsonPath("$.comprovativoUrl").isString());
    }

    private JsonNode estado(String token) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/api/addons/" + ADDON_KEY + "/estado").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private void tornarVencido(int diasNoPassado) {
        porNoPlano("PRO", 2);
        jdbcTemplate.update("DELETE FROM company_addons WHERE company_id = ? AND addon_key = ?", companyId(), ADDON_KEY);
        jdbcTemplate.update("INSERT INTO company_addons (id, company_id, addon_key, status, activated_at, valido_ate, updated_at) VALUES (?, ?, ?, 'ATIVO', now(), ?, now())",
                UUID.randomUUID().toString(), companyId(), ADDON_KEY, Timestamp.from(Instant.now().minus(diasNoPassado, ChronoUnit.DAYS)));
        entityManager.clear();
    }

    @Test
    void catalogoRbacEGateDePlano() throws Exception {
        mockMvc.perform(get("/api/addons/catalogo")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/addons/catalogo").header("Authorization", "Bearer " + login(COMPRADOR_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.addonKey == 'PO_ROBOT')].requerPlano").value("PRO"))
                // Preço de tabela é aritmética JS no Node → número (ao contrário das colunas Decimal, que saem como texto).
                .andExpect(jsonPath("$[0].preco.valorUsd").value(org.hamcrest.Matchers.greaterThan(0)));

        porNoPlano("PRO", 2);
        mockMvc.perform(post("/api/addons/" + ADDON_KEY + "/pedir").header("Authorization", "Bearer " + login(FINANCEIRO_EMAIL))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/addons/" + ADDON_KEY + "/pedir").header("Authorization", "Bearer " + login(COMPRADOR_EMAIL))).andExpect(status().isForbidden());

        String companyAdmin = login(COMPANY_ADMIN_EMAIL);
        porNoPlano("CORE", 1);
        JsonNode recusa = pedir(companyAdmin, 400);
        assertThat(recusa.get("error").get("message").asText()).contains("PRO");

        porNoPlano("PRO", 2);
        pedir(companyAdmin, 201);
        pedir(companyAdmin, 409); // só pode haver uma cobrança em aberto por vez
    }

    /** `uploadDocuments` (10MB) em addonRoutes.js: acima disso é o 413 LIMIT_FILE_SIZE do multer, não um 422 nosso. */
    @Test
    void comprovativoAcimaDe10MbDa413ComoOMulter() throws Exception {
        porNoPlano("PRO", 2);
        String companyAdmin = login(COMPANY_ADMIN_EMAIL);
        String id = pedir(companyAdmin, 201).get("id").asText();

        byte[] grande = new byte[10 * 1024 * 1024 + 1];
        System.arraycopy(COMPROVATIVO, 0, grande, 0, COMPROVATIVO.length);
        mockMvc.perform(multipart("/api/addons/" + id + "/comprovativo")
                        .file(new MockMultipartFile("comprovativo", "enorme.pdf", "application/pdf", grande))
                        .header("Authorization", "Bearer " + companyAdmin))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.error.code").value("LIMIT_FILE_SIZE"))
                .andExpect(jsonPath("$.error.message").value("O ficheiro é demasiado grande. Reduza o tamanho da imagem e tente novamente."));
        assertThat(estado(companyAdmin).get("emAberto").get("status").asText()).isEqualTo("PENDENTE");
        comprovativo(companyAdmin, id);
    }

    @Test
    void fluxoCompletoAteOAddonAtivoEValidade() throws Exception {
        porNoPlano("PRO", 2);
        String companyAdmin = login(COMPANY_ADMIN_EMAIL);
        String adminSistema = login(ADMIN_SISTEMA_EMAIL);

        JsonNode cobranca = pedir(companyAdmin, 201);
        String id = cobranca.get("id").asText();
        assertThat(cobranca.get("referencia").asText()).startsWith("ADD-");
        assertThat(cobranca.get("status").asText()).isEqualTo("PENDENTE");
        JsonNode antes = estado(companyAdmin);
        assertThat(antes.get("ativo").asBoolean()).isFalse();
        assertThat(antes.get("emAberto").get("id").asText()).isEqualTo(id);

        // Sem comprovativo, confirmar é recusado; o Company Admin não confirma.
        mockMvc.perform(post("/api/addons/" + id + "/confirmar").header("Authorization", "Bearer " + adminSistema).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        comprovativo(companyAdmin, id);
        assertThat(estado(companyAdmin).get("ativo").asBoolean()).isFalse();
        mockMvc.perform(post("/api/addons/" + id + "/confirmar").header("Authorization", "Bearer " + companyAdmin).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/addons/fila").header("Authorization", "Bearer " + companyAdmin)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/addons/fila").header("Authorization", "Bearer " + adminSistema))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.porConfirmar").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.emAberto").isArray());

        // A confirmação da KIXIMA é o único sítio onde o add-on liga.
        mockMvc.perform(post("/api/addons/" + id + "/confirmar").header("Authorization", "Bearer " + adminSistema).contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMADA"));
        JsonNode depois = estado(companyAdmin);
        assertThat(depois.get("ativo").asBoolean()).isTrue();
        assertThat(depois.get("activatedAt").asText()).isNotBlank();
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT status::text FROM company_addons WHERE company_id = ? AND addon_key = ?", String.class, companyId(), ADDON_KEY)).isEqualTo("ATIVO");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE entity_type = 'AddonCobranca' AND entity_id = ? AND action = 'ADDON_CONFIRMADO'", Integer.class, id)).isEqualTo(1);
        // Já ativo: não se pede outra vez.
        pedir(companyAdmin, 409);

        // N4 — vencimento: ATIVO na BD, mas ativo:false; bloqueia o robot com mensagem de vencimento; pode ser pedido de novo;
        // renovar conta a validade a partir de HOJE.
        tornarVencido(100);
        assertThat(estado(companyAdmin).get("ativo").asBoolean()).isFalse();
        assertThat(estado(companyAdmin).get("validoAte").asText()).isNotBlank();
        String productId = jdbcTemplate.queryForObject("SELECT id FROM products ORDER BY name LIMIT 1", String.class);
        mockMvc.perform(get("/api/po-robot/media-sugerida/" + productId).header("Authorization", "Bearer " + companyAdmin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsStringIgnoringCase("venceu")));
        String renovacao = pedir(companyAdmin, 201).get("id").asText();
        comprovativo(companyAdmin, renovacao);
        mockMvc.perform(post("/api/addons/" + renovacao + "/confirmar").header("Authorization", "Bearer " + adminSistema).contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        entityManager.flush();
        Timestamp validoAte = jdbcTemplate.queryForObject("SELECT valido_ate FROM company_addons WHERE company_id = ? AND addon_key = ?", Timestamp.class, companyId(), ADDON_KEY);
        double dias = (validoAte.getTime() - System.currentTimeMillis()) / (24.0 * 60 * 60 * 1000);
        assertThat(dias).isBetween(20.0, 40.0);

        // Cancelar liberta a empresa para pedir de novo.
        tornarVencido(10);
        String c = pedir(companyAdmin, 201).get("id").asText();
        mockMvc.perform(post("/api/addons/" + c + "/cancelar").header("Authorization", "Bearer " + companyAdmin)
                        .contentType("application/json").content("{\"motivo\":\"Pedido por engano\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELADA"));
        pedir(companyAdmin, 201);
    }
}
