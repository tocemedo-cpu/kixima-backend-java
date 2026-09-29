package ao.kixima;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Sem {@code @Transactional} de propósito. Os testes de paridade correm cada
 * pedido dentro de UMA transação aberta pelo próprio teste, o que esconde uma
 * classe inteira de avarias: relações lazy do JPA lidas depois de a transação
 * do serviço fechar ("could not initialize proxy - no Session"), que em
 * produção são um 500. O replay de contrato do M7 apanhou exactamente isso em
 * {@code GET /api/purchase-orders/{id}}. Este teste percorre as leituras de
 * todas as personas como o servidor real as executa (open-in-view desligado,
 * uma transação por chamada ao serviço) e recusa qualquer 5xx.
 * Só GETs: não deixa rasto na base partilhada pelos outros testes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SemSessaoSmokeTest {

    private static final String PASSWORD = "Kixima@123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String login(String email) throws Exception {
        var res = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andReturn();
        assertThat(res.getResponse().getStatus()).as("login " + email).isEqualTo(200);
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void leiturasDeTodasAsPersonasNuncaDao5xxForaDeUmaTransacaoDeTeste() throws Exception {
        String comprador = login("comprador@petroangola.co.ao");
        String companyAdmin = login("admin@petroangola.co.ao");
        String financeiro = login("financeiro@petroangola.co.ao");
        String fornecedor = login("fornecedor@kianda.co.ao");
        String admin = login("admin@kixima.co.ao");
        String compradora = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = 'AO-CLI-0001'", String.class);
        String fornecedora = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = 'AO-FOR-0001'", String.class);
        String produto = jdbcTemplate.queryForObject("SELECT id FROM products WHERE supplier_id = ? ORDER BY name LIMIT 1", String.class, fornecedora);
        List<String> pos = jdbcTemplate.queryForList("SELECT id FROM purchase_orders WHERE buyer_company_id = ? ORDER BY created_at LIMIT 1", String.class, compradora);
        List<String> faturas = jdbcTemplate.queryForList("SELECT id FROM invoices ORDER BY issued_at LIMIT 1", String.class);
        List<String> contratos = jdbcTemplate.queryForList("SELECT id FROM contracts ORDER BY created_at LIMIT 1", String.class);

        List<String[]> pedidos = new ArrayList<>(List.of(
                new String[]{comprador, "/api/auth/me"},
                new String[]{comprador, "/api/catalog"},
                new String[]{comprador, "/api/catalog/" + produto},
                new String[]{comprador, "/api/catalog/" + produto + "/reviews"},
                new String[]{fornecedor, "/api/catalog/documents"},
                new String[]{fornecedor, "/api/catalog/movements"},
                new String[]{fornecedor, "/api/catalog/api-keys"},
                new String[]{comprador, "/api/marketplace/search?limit=5"},
                new String[]{comprador, "/api/marketplace/facets"},
                new String[]{comprador, "/api/marketplace/suppliers"},
                new String[]{comprador, "/api/marketplace/compare?productId=" + produto},
                new String[]{comprador, "/api/marketplace/favorites"},
                new String[]{comprador, "/api/marketplace/saved-searches"},
                new String[]{comprador, "/api/dashboard/comprador"},
                new String[]{comprador, "/api/buyer/orders"},
                new String[]{comprador, "/api/buyer/payments"},
                new String[]{comprador, "/api/buyer/deliveries"},
                new String[]{comprador, "/api/buyer/receptions"},
                new String[]{comprador, "/api/buyer/suppliers"},
                new String[]{comprador, "/api/buyer/activities"},
                new String[]{comprador, "/api/buyer/profile"},
                new String[]{financeiro, "/api/financeiro/overview"},
                new String[]{financeiro, "/api/financeiro/invoices"},
                new String[]{financeiro, "/api/financeiro/payments"},
                new String[]{financeiro, "/api/payments/invoices/pending"},
                new String[]{financeiro, "/api/payments/history"},
                new String[]{companyAdmin, "/api/company-admin/dashboard"},
                new String[]{companyAdmin, "/api/company-admin/organizacao"},
                new String[]{companyAdmin, "/api/company-admin/activities"},
                new String[]{companyAdmin, "/api/company-admin/reports"},
                new String[]{companyAdmin, "/api/company-admin/settings"},
                new String[]{admin, "/api/companies"},
                new String[]{companyAdmin, "/api/companies/" + compradora},
                new String[]{companyAdmin, "/api/companies/" + compradora + "/subscription"},
                new String[]{companyAdmin, "/api/companies/" + compradora + "/bank-details"},
                new String[]{admin, "/api/companies/" + compradora + "/platform-fees"},
                new String[]{companyAdmin, "/api/companies/users"},
                new String[]{companyAdmin, "/api/companies/invites"},
                new String[]{companyAdmin, "/api/assinatura"},
                new String[]{companyAdmin, "/api/assinatura/canais"},
                new String[]{admin, "/api/assinatura/fila"},
                new String[]{companyAdmin, "/api/addons/catalogo"},
                new String[]{companyAdmin, "/api/addons/PO_ROBOT/estado"},
                new String[]{admin, "/api/addons/fila"},
                new String[]{comprador, "/api/quotes"},
                new String[]{fornecedor, "/api/quotes"},
                new String[]{comprador, "/api/contracts"},
                new String[]{admin, "/api/contracts"},
                new String[]{fornecedor, "/api/policies/company"},
                new String[]{fornecedor, "/api/users/profile"},
                new String[]{comprador, "/api/users/profile"},
                new String[]{admin, "/api/users/profile"},
                new String[]{fornecedor, "/api/users/me"},
                new String[]{fornecedor, "/api/notifications"},
                new String[]{fornecedor, "/api/reports/fornecedor"},
                new String[]{fornecedor, "/api/kits"},
                new String[]{companyAdmin, "/api/po-robot/regras"},
                new String[]{comprador, "/api/support/overview"},
                new String[]{comprador, "/api/support/tickets"},
                new String[]{comprador, "/api/support/unread-count"},
                new String[]{admin, "/api/support/admin/overview"},
                new String[]{admin, "/api/support/admin/tickets"},
                new String[]{admin, "/api/support/admin/queue"},
                new String[]{admin, "/api/support/admin/my-tickets"},
                new String[]{comprador, "/api/conversations"},
                new String[]{fornecedor, "/api/purchase-orders"},
                new String[]{comprador, "/api/purchase-orders"},
                new String[]{admin, "/api/admin/platform-fees"},
                new String[]{admin, "/api/admin/users"},
                new String[]{admin, "/api/admin/invites"},
                new String[]{admin, "/api/admin/activities"},
                new String[]{admin, "/api/admin/audit-logs?limit=5"},
                new String[]{admin, "/api/admin/prontidao"},
                new String[]{admin, "/api/admin/mfa-pendentes"},
                new String[]{admin, "/api/admin/feedback"},
                new String[]{admin, "/api/faturacao/metricas"},
                new String[]{admin, "/api/faturacao/integridade"},
                new String[]{admin, "/api/faturacao/agt-series-fe"},
                new String[]{admin, "/api/conciliacao/por-resolver"},
                new String[]{admin, "/api/conciliacao/canais"},
                new String[]{admin, "/api/category-management/admin/thresholds"},
                new String[]{admin, "/api/supplier-development/requests"},
                new String[]{null, "/api/supplier-development/fee"},
                new String[]{null, "/api/planos"},
                new String[]{null, "/api/retencao"},
                new String[]{null, "/api/public/stats"},
                new String[]{null, "/api/public/feedback"},
                new String[]{null, "/health"},
                new String[]{null, "/ready"}));
        for (String po : pos) {
            pedidos.add(new String[]{comprador, "/api/purchase-orders/" + po});
            pedidos.add(new String[]{comprador, "/api/purchase-orders/" + po + "/history"});
        }
        for (String fatura : faturas) {
            pedidos.add(new String[]{admin, "/api/payments/invoices/" + fatura + "/notas-credito"});
        }
        for (String contrato : contratos) pedidos.add(new String[]{admin, "/api/contracts/" + contrato});

        List<String> falhas = new ArrayList<>();
        for (String[] p : pedidos) {
            var b = get(p[1]);
            if (p[0] != null) b.header("Authorization", "Bearer " + p[0]);
            var res = mockMvc.perform(b).andReturn().getResponse();
            if (res.getStatus() != 200) System.out.println("[smoke] " + res.getStatus() + " " + p[1]);
            if (res.getStatus() >= 500) falhas.add(res.getStatus() + " " + p[1] + " → " + res.getContentAsString().replaceAll("\\s+", " ").substring(0, Math.min(160, res.getContentAsString().length())));
        }
        assertThat(falhas).as("pedidos com 5xx fora de uma transação de teste").isEmpty();
    }
}
