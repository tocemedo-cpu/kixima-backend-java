package ao.kixima.po;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de paridade de contrato (plano, secção 4) para o troço central de
 * poController.js/poService.js — checkout → aprovação → aceitação com
 * geração de fatura (imposto + cadeia de hash da série do fornecedor) —
 * contra os dados já semeados em prisma/seed.demo.js, na mesma base de
 * teste local do Node.
 *
 * `serie_fiscal` do fornecedor de demonstração vem NULL do seed — ajusta-se
 * aqui por SQL directo, dentro da mesma transação (`@Transactional` reverte
 * no fim do teste), para exercitar o {@code SELECT ... FOR UPDATE} real de
 * FaturacaoService.atribuir, não só o caminho "sem série" de
 * Certificacao.VAZIA.
 *
 * {@code entityManager.flush()}+{@code clear()} depois de cada chamada que muda de estado:
 * em produção, cada pedido HTTP tem a sua própria sessão Hibernate
 * (`open-in-view: false`) — `findById` num pedido seguinte faz sempre um
 * SELECT novo, com as associações (`supplierCompany`, `items`) resolvidas a
 * partir da FK na base. Aqui, ao contrário, todo o fluxo corre dentro de
 * UMA ÚNICA transacção/sessão (`@Transactional` do teste, para poder
 * reverter tudo no fim) — sem o `clear()`, o `findById` de um passo
 * seguinte devolveria a MESMA instância Java já gerida da criação
 * (mapa de identidade do Hibernate), com essas associações nunca
 * hidratadas a partir da base. `clear()` força cada passo a reflectir uma
 * sessão nova, tal como aconteceria com pedidos HTTP separados.
 *
 * `dispatch`/`markDelivered`/`confirmReception` (estados PAGA em diante)
 * não são exercitados aqui — dependem do domínio Pagamento, ainda não
 * portado (ver PoService, cabeçalho).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PoControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";
    private static final String COMPANY_ADMIN_EMAIL = "admin@petroangola.co.ao";
    private static final String FORNECEDOR_EMAIL = "fornecedor@kianda.co.ao";
    private static final String SUPPLIER_TAX_ID = "AO-FOR-0001";
    private static final String PRODUCT_NAME = "Mangueira hidráulica de alta pressão 2\"";

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

    private String supplierCompanyId() {
        return jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = ?", String.class, SUPPLIER_TAX_ID);
    }

    private String productId(String supplierCompanyId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE supplier_id = ? AND name = ?", String.class, supplierCompanyId, PRODUCT_NAME);
    }

    @Test
    void fluxoCompletoDeCheckoutAteAceitacaoComFaturaCertificada() throws Exception {
        String supplierCompanyId = supplierCompanyId();
        String productId = productId(supplierCompanyId);
        // Série fiscal do fornecedor de demo vem NULL do seed — define-se aqui para
        // exercitar a cadeia de hash real (SELECT ... FOR UPDATE), dentro da transação do teste.
        jdbcTemplate.update("UPDATE companies SET serie_fiscal = ? WHERE id = ?", "FT KIANDA", supplierCompanyId);
        entityManager.flush();
        entityManager.clear();

        String compradorToken = login(COMPRADOR_EMAIL);
        String companyAdminToken = login(COMPANY_ADMIN_EMAIL);
        String fornecedorToken = login(FORNECEDOR_EMAIL);

        String body = objectMapper.writeValueAsString(Map.of(
                "supplierCompanyId", supplierCompanyId,
                "items", List.of(Map.of("productId", productId, "quantity", 2))));

        // 1. Checkout — Comprador cria a PO. unitPrice=120000, kind=PRODUTO (sem retenção): net=240000, iva=33600, gross=273600.
        var createRes = mockMvc.perform(post("/api/purchase-orders")
                        .header("Authorization", "Bearer " + compradorToken)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AGUARDANDO_APROVACAO"))
                .andExpect(jsonPath("$.totalAmount").value(273600.0))
                .andExpect(jsonPath("$.netAmount").value(240000.0))
                .andExpect(jsonPath("$.taxAmount").value(33600.0))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andReturn();
        String poId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asText();
        entityManager.flush();
        entityManager.clear();

        // Só o Company Admin da compradora aprova — o próprio Comprador não pode (RBAC).
        mockMvc.perform(patch("/api/purchase-orders/" + poId + "/approve")
                        .header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().isForbidden());

        // 2. Aprovação.
        mockMvc.perform(patch("/api/purchase-orders/" + poId + "/approve")
                        .header("Authorization", "Bearer " + companyAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APROVADA"))
                .andExpect(jsonPath("$.approvedById").isString());
        entityManager.flush();
        entityManager.clear();

        // Repetir a aprovação já não é um estado válido.
        mockMvc.perform(patch("/api/purchase-orders/" + poId + "/approve")
                        .header("Authorization", "Bearer " + companyAdminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
        entityManager.flush();
        entityManager.clear();

        // 3/4. Fornecedor aceita -> gera a fatura certificada (prazo de pagamento + hash chain).
        mockMvc.perform(patch("/api/purchase-orders/" + poId + "/accept")
                        .header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AGUARDANDO_PAGAMENTO"))
                .andExpect(jsonPath("$.acceptedAt").isString())
                .andExpect(jsonPath("$.paymentDueAt").isString());
        entityManager.flush();
        entityManager.clear();

        Map<String, Object> invoice = jdbcTemplate.queryForMap(
                "SELECT amount, net_amount, tax_amount, serie, numero_na_serie, hash_documento, referencia_pagamento "
                        + "FROM invoices WHERE purchase_order_id = ?", poId);
        assertEquals(0, new BigDecimal("273600.00").compareTo((BigDecimal) invoice.get("amount")));
        assertEquals(0, new BigDecimal("240000.00").compareTo((BigDecimal) invoice.get("net_amount")));
        assertEquals(0, new BigDecimal("33600.00").compareTo((BigDecimal) invoice.get("tax_amount")));
        assertEquals("FT KIANDA", invoice.get("serie"));
        assertEquals(1, invoice.get("numero_na_serie"));
        assertNotNull(invoice.get("hash_documento"));
        assertNotNull(invoice.get("referencia_pagamento"));

        Integer linhas = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoice_lines WHERE invoice_id = (SELECT id FROM invoices WHERE purchase_order_id = ?)",
                Integer.class, poId);
        assertEquals(1, linhas);

        // GET individual reflecte o mesmo estado.
        mockMvc.perform(get("/api/purchase-orders/" + poId).header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AGUARDANDO_PAGAMENTO"));

        // Listagem filtrada por estado para a empresa fornecedora.
        mockMvc.perform(get("/api/purchase-orders").param("status", "AGUARDANDO_PAGAMENTO")
                        .header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + poId + "')]").exists());
    }

    @Test
    void naoPodeComprarDaPropriaEmpresa() throws Exception {
        String buyerCompanyId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = 'AO-CLI-0001'", String.class);
        String anyProductId = jdbcTemplate.queryForObject("SELECT id FROM products LIMIT 1", String.class);
        String compradorToken = login(COMPRADOR_EMAIL);

        mockMvc.perform(post("/api/purchase-orders")
                        .header("Authorization", "Bearer " + compradorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "supplierCompanyId", buyerCompanyId,
                                "items", List.of(Map.of("productId", anyProductId, "quantity", 1))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    void criarPoSemSessaoDevolve401() throws Exception {
        mockMvc.perform(post("/api/purchase-orders")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "supplierCompanyId", "qualquer",
                                "items", List.of(Map.of("productId", "qualquer", "quantity", 1))))))
                .andExpect(status().isUnauthorized());
    }
}
