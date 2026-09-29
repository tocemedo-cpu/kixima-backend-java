package ao.kixima.painel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Espelha tests/companyAdmin.test.js, buyer.test.js, financeiro.test.js, dashboard.test.js e public-stats.test.js. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PaineisControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String COMPANY_ADMIN_EMAIL = "admin@petroangola.co.ao";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";
    private static final String FINANCEIRO_EMAIL = "financeiro@petroangola.co.ao";
    private static final String FORNECEDOR_EMAIL = "fornecedor@kianda.co.ao";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String login(String email) throws Exception {
        var res = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    private JsonNode getJson(String path, String token) throws Exception {
        var res = mockMvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    @Test
    void companyAdminTelasAgregadas() throws Exception {
        String adminToken = login(COMPANY_ADMIN_EMAIL);
        String compradorToken = login(COMPRADOR_EMAIL);
        String fornecedorToken = login(FORNECEDOR_EMAIL);

        JsonNode dash = getJson("/api/company-admin/dashboard", adminToken);
        assertThat(dash.get("kpis").has("pedidos")).isTrue();
        assertThat(dash.get("volume")).hasSize(6);
        assertThat(dash.get("volume").get(0).get("label").asText()).endsWith(".");
        assertThat(dash.get("resumo").has("usuariosAtivos")).isTrue();
        assertThat(dash.get("recent").isArray()).isTrue();

        JsonNode org = getJson("/api/company-admin/organizacao", adminToken);
        assertThat(org.get("company").get("name").asText()).isNotBlank();
        assertThat(org.get("summary").get("users").asInt()).isGreaterThan(0);

        // Só o Company Admin — nem Vendedor nem Comprador.
        mockMvc.perform(get("/api/company-admin/organizacao").header("Authorization", "Bearer " + fornecedorToken)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/company-admin/dashboard").header("Authorization", "Bearer " + fornecedorToken)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/company-admin/organizacao").header("Authorization", "Bearer " + compradorToken)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/company-admin/dashboard").header("Authorization", "Bearer " + compradorToken)).andExpect(status().isForbidden());

        JsonNode act = getJson("/api/company-admin/activities", adminToken);
        assertThat(act.get("kpis").has("total")).isTrue();
        assertThat(act.get("items")).allSatisfy(i -> {
            assertThat(i.get("title").asText()).isNotBlank();
            assertThat(i.get("status").asText()).isNotBlank();
        });
        JsonNode pendentes = getJson("/api/company-admin/activities?filter=PENDENTE", adminToken);
        assertThat(pendentes.get("items")).allSatisfy(i -> assertThat(i.get("status").asText()).isEqualTo("PENDENTE"));

        JsonNode rep = getJson("/api/company-admin/reports", adminToken);
        assertThat(rep.get("series")).hasSize(6);
        assertThat(rep.get("catalog").size()).isGreaterThan(0);

        mockMvc.perform(get("/api/company-admin/settings").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aprovacaoObrigatoria").value(true));
        mockMvc.perform(put("/api/company-admin/settings").header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content("{\"valoresSemImpostos\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valoresSemImpostos").value(true))
                .andExpect(jsonPath("$.aprovacaoObrigatoria").value(true));
        mockMvc.perform(get("/api/company-admin/settings").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.valoresSemImpostos").value(true));
    }

    @Test
    void compradorTelasAgregadas() throws Exception {
        String compradorToken = login(COMPRADOR_EMAIL);

        JsonNode orders = getJson("/api/buyer/orders", compradorToken);
        assertThat(orders.get("kpis").get("total").asInt()).isGreaterThan(0);
        assertThat(orders.get("items").size()).isGreaterThan(0);
        JsonNode po = orders.get("items").get(0);
        assertThat(po.get("reference").asText()).startsWith("PO-");
        assertThat(po.get("supplier").get("name").asText()).isNotBlank();
        assertThat(po.get("itemsCount").asInt()).isGreaterThan(0);
        assertThat(orders.get("pages").asInt()).isGreaterThanOrEqualTo(1);

        JsonNode canceladas = getJson("/api/buyer/orders?status=CANCELADAS", compradorToken);
        assertThat(canceladas.get("items")).allSatisfy(p -> assertThat(List.of("REJEITADA", "RECUSADA_FORNECEDOR")).contains(p.get("status").asText()));
        JsonNode pesquisa = getJson("/api/buyer/orders?q=kianda&limit=2", compradorToken);
        assertThat(pesquisa.get("items").size()).isBetween(1, 2);

        JsonNode payments = getJson("/api/buyer/payments", compradorToken);
        assertThat(payments.get("kpis").has("aPagar")).isTrue();
        assertThat(payments.get("items")).allSatisfy(r -> {
            assertThat(r.get("reference").asText()).isNotBlank();
            assertThat(r.get("status").asText()).isNotBlank();
        });
        assertThat(getJson("/api/buyer/payments?status=CONCLUIDO", compradorToken).get("items"))
                .allSatisfy(r -> assertThat(r.get("status").asText()).isEqualTo("PAGA"));

        JsonNode deliveries = getJson("/api/buyer/deliveries", compradorToken);
        assertThat(deliveries.get("items")).allSatisfy(d -> {
            assertThat(d.get("progress").isNumber()).isTrue();
            assertThat(d.get("stage").asText()).isNotBlank();
        });

        JsonNode receptions = getJson("/api/buyer/receptions", compradorToken);
        assertThat(receptions.get("kpis").has("total")).isTrue();
        assertThat(receptions.get("items")).allSatisfy(r -> {
            assertThat(r.get("reference").asText()).isNotBlank();
            assertThat(r.get("status").asText()).isNotBlank();
        });

        JsonNode suppliers = getJson("/api/buyer/suppliers", compradorToken);
        assertThat(suppliers.get("items").size()).isGreaterThan(0);
        assertThat(suppliers.get("items").get(0).has("rating")).isTrue();
        assertThat(suppliers.get("items").get(0).get("productCount").asInt()).isGreaterThan(0);
        assertThat(suppliers.get("kpis").has("homologados")).isTrue();

        JsonNode activities = getJson("/api/buyer/activities", compradorToken);
        assertThat(activities.get("items").size()).isGreaterThan(0);
        assertThat(activities.get("items")).allSatisfy(t -> {
            assertThat(t.get("title").asText()).isNotBlank();
            assertThat(t.get("status").asText()).isNotBlank();
        });

        JsonNode profile = getJson("/api/buyer/profile", compradorToken);
        assertThat(profile.get("user").get("email").asText()).isEqualTo(COMPRADOR_EMAIL);
        assertThat(profile.get("company").get("name").asText()).isNotBlank();
        assertThat(profile.get("summary").has("ordersYear")).isTrue();

        mockMvc.perform(get("/api/buyer/orders").header("Authorization", "Bearer " + login(FORNECEDOR_EMAIL))).andExpect(status().isForbidden());
    }

    @Test
    void financeiroEDashboardDoComprador() throws Exception {
        String finToken = login(FINANCEIRO_EMAIL);
        String fornecedorToken = login(FORNECEDOR_EMAIL);
        String compradorToken = login(COMPRADOR_EMAIL);

        JsonNode overview = getJson("/api/financeiro/overview", finToken);
        assertThat(overview.get("kpis").has("pagamentosPendentes")).isTrue();
        assertThat(overview.get("series")).hasSize(6);
        assertThat(overview.get("pendentes").isArray()).isTrue();

        JsonNode invoices = getJson("/api/financeiro/invoices", finToken);
        assertThat(invoices.get("kpis").has("pendentes")).isTrue();
        assertThat(invoices.get("items")).allSatisfy(i -> {
            assertThat(i.get("reference").asText()).startsWith("FAT-");
            assertThat(i.get("supplier").asText()).isNotBlank();
        });

        JsonNode payments = getJson("/api/financeiro/payments", finToken);
        assertThat(payments.get("kpis").has("aPagar")).isTrue();
        assertThat(payments.get("items").size()).isGreaterThan(0);
        JsonNode pagas = getJson("/api/financeiro/payments?status=PAGO", finToken);
        assertThat(pagas.get("items").size()).isGreaterThan(0);
        assertThat(pagas.get("items")).allSatisfy(i -> assertThat(i.get("status").asText()).isEqualTo("PAGA"));
        mockMvc.perform(get("/api/financeiro/overview").header("Authorization", "Bearer " + fornecedorToken)).andExpect(status().isForbidden());

        JsonNode dash = getJson("/api/dashboard/comprador", compradorToken);
        assertThat(dash.get("kpis").has("emAndamento")).isTrue();
        assertThat(dash.get("kpis").get("aguardandoPagamento").has("total")).isTrue();
        assertThat(dash.get("minhasOrdens")).hasSize(5);
        mockMvc.perform(get("/api/dashboard/comprador").header("Authorization", "Bearer " + fornecedorToken)).andExpect(status().isForbidden());
    }

    @Test
    void estatisticasPublicasSemAutenticacaoReflectemABase() throws Exception {
        int aprovadas = jdbcTemplate.queryForObject("SELECT count(*) FROM companies WHERE status = 'APROVADA'", Integer.class);
        int fornecedores = jdbcTemplate.queryForObject("SELECT count(*) FROM companies WHERE status = 'APROVADA' AND type = 'FORNECEDOR'", Integer.class);
        mockMvc.perform(get("/api/public/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.empresasVerificadas").value(aprovadas))
                .andExpect(jsonPath("$.fornecedoresQualificados").value(fornecedores))
                .andExpect(jsonPath("$.ordensProcessadas").isNumber())
                .andExpect(jsonPath("$.pagamentoSlaDias").value(7));
        assertThat(fornecedores).isLessThanOrEqualTo(aprovadas);
    }
}
