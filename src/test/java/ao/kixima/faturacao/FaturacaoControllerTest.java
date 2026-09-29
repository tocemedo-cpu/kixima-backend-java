package ao.kixima.faturacao;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Espelha tests/faturacao-agt.test.js (cadeia de integridade, SAF-T) e
 * tests/metricas.test.js — o troço de faturacaoRoutes.js portado em D.5.
 * As faturas de teste são emitidas com a MESMA numeração certificada
 * (FaturacaoService.atribuir), tal como `emitir()` no Node.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FaturacaoControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String ADMIN_SISTEMA_EMAIL = "admin@kixima.co.ao";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";
    private static final String FORNECEDOR_EMAIL = "fornecedor@kianda.co.ao";
    private static final String SERIE = "TESTE";
    private static final String SERIE_NC = SERIE + "-NC";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FaturacaoService faturacaoService;

    @Autowired
    private CadeiaIntegridadeService cadeiaIntegridadeService;

    @Autowired
    private SaftService saftService;

    @Autowired
    private MetricasService metricasService;

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

    private String companyId(String taxId) {
        return jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = ?", String.class, taxId);
    }

    private String criarPO(String supplierCompanyId) {
        String compradorId = companyId("AO-CLI-0001");
        String userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", String.class, COMPRADOR_EMAIL);
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO purchase_orders (id, reference, buyer_company_id, supplier_company_id, created_by_id, total_amount, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 1, 'CONCLUIDA'::\"PoStatus\", now(), now())",
                id, "PO-TESTE-SAFT-" + id.substring(0, 8), compradorId, supplierCompanyId, userId);
        return id;
    }

    /** A fatura mínima com numeração certificada — `emitir()` do Node. Devolve o id. */
    private Map<String, Object> emitir(long total, String purchaseOrderId) {
        Instant agora = Instant.now();
        FaturacaoService.Certificacao c = faturacaoService.atribuir(agora, BigDecimal.valueOf(total), SERIE, null);
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO invoices (id, reference, purchase_order_id, amount, net_amount, tax_amount, currency, issued_at, due_at, "
                        + "serie, numero_na_serie, hash_documento, hash_anterior, assinada_em, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 0, 'AOA', ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, "FAT-TESTE-" + c.numeroNaSerie() + "-" + id.substring(0, 6), purchaseOrderId, total, total,
                Timestamp.from(agora), Timestamp.from(agora.plus(7, ChronoUnit.DAYS)), c.serie(), c.numeroNaSerie(),
                c.hashDocumento(), c.hashAnterior(), Timestamp.from(c.assinadaEm()), Timestamp.from(agora), Timestamp.from(agora));
        entityManager.clear();
        return Map.of("id", id, "serie", c.serie(), "numeroNaSerie", c.numeroNaSerie(), "ano", agora.atZone(ZoneOffset.UTC).getYear());
    }

    private String emitirDoFornecedor(long total) {
        return (String) emitir(total, criarPO(companyId("AO-FOR-0001"))).get("id");
    }

    private void linha(String invoiceId, String code, String desc, int qty, long unit, long net, long iva, String taxCode) {
        jdbcTemplate.update("INSERT INTO invoice_lines (id, invoice_id, line_number, product_code, description, quantity, unit_price, net_amount, iva_amount, iva_tax_code, created_at) "
                        + "VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?, ?, now())",
                UUID.randomUUID().toString(), invoiceId, code, desc, qty, unit, net, iva, taxCode);
        entityManager.clear();
    }

    private Map<String, Object> emitirNotaCredito(String invoiceId, long valor, String motivo) {
        Instant agora = Instant.now();
        FaturacaoService.Certificacao c = faturacaoService.atribuir(agora, BigDecimal.valueOf(valor), SERIE_NC, null);
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO credit_notes (id, reference, invoice_id, motivo, amount, net_amount, tax_amount, currency, serie, numero_na_serie, "
                        + "hash_documento, hash_anterior, assinada_em, issued_at, created_at) VALUES (?, ?, ?, ?, ?, ?, 0, 'AOA', ?, ?, ?, ?, ?, ?, ?)",
                id, "NC-TESTE-" + c.numeroNaSerie() + "-" + id.substring(0, 6), invoiceId, motivo, valor, valor, c.serie(), c.numeroNaSerie(),
                c.hashDocumento(), c.hashAnterior(), Timestamp.from(c.assinadaEm()), Timestamp.from(agora), Timestamp.from(agora));
        entityManager.clear();
        return Map.of("id", id, "serie", c.serie(), "numeroNaSerie", c.numeroNaSerie(), "ano", agora.atZone(ZoneOffset.UTC).getYear());
    }

    private static String numero(Map<String, Object> doc) {
        return FaturacaoService.numeroDocumentoAGT((String) doc.get("serie"), (Integer) doc.get("ano"), (Integer) doc.get("numeroNaSerie"));
    }

    private static long contar(String xml, String literal) {
        Matcher m = Pattern.compile(Pattern.quote(literal)).matcher(xml);
        long n = 0;
        while (m.find()) n++;
        return n;
    }

    // --- Cadeia de integridade ---------------------------------------------------------

    @Test
    void cadeiaDeIntegridadeDetetaAlteracoesEBuracos() throws Exception {
        // Íntegra quando está.
        Map<String, Object> a = emitir(1000, null);
        Map<String, Object> b = emitir(2000, null);
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT hash_anterior FROM invoices WHERE id = ?", String.class, a.get("id"))).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT hash_anterior FROM invoices WHERE id = ?", String.class, b.get("id")))
                .isEqualTo(jdbcTemplate.queryForObject("SELECT hash_documento FROM invoices WHERE id = ?", String.class, a.get("id")));
        Map<String, Object> r = cadeiaIntegridadeService.verificarCadeia(SERIE, 2026);
        assertThat(r.get("documentos")).isEqualTo(2);
        assertThat(r.get("integra")).isEqualTo(true);
        assertThat((List<?>) r.get("problemas")).isEmpty();

        // Alterar o valor de uma fatura emitida é DETETADO — em TODAS as alteradas, não só na primeira.
        jdbcTemplate.update("UPDATE invoices SET amount = 999999 WHERE id = ?", a.get("id"));
        jdbcTemplate.update("UPDATE invoices SET amount = 222 WHERE id = ?", b.get("id"));
        entityManager.clear();
        r = cadeiaIntegridadeService.verificarCadeia(SERIE, 2026);
        assertThat(r.get("integra")).isEqualTo(false);
        @SuppressWarnings("unchecked") List<Map<String, Object>> problemas = (List<Map<String, Object>>) r.get("problemas");
        assertThat(problemas.stream().filter(p -> "DOCUMENTO_ALTERADO".equals(p.get("tipo"))).count()).isEqualTo(2);

        // Apagar uma fatura do meio parte a numeração E a cadeia.
        Map<String, Object> meio = emitir(3000, null);
        emitir(4000, null);
        jdbcTemplate.update("DELETE FROM invoices WHERE id = ?", meio.get("id"));
        entityManager.clear();
        r = cadeiaIntegridadeService.verificarCadeia(SERIE, 2026);
        @SuppressWarnings("unchecked") List<Map<String, Object>> tipos = (List<Map<String, Object>>) r.get("problemas");
        assertThat(tipos.stream().map(p -> (String) p.get("tipo")).toList()).contains("BURACO_NA_NUMERACAO", "ELO_PARTIDO");

        // Exige um código de série — já não há uma série global única.
        assertThat(cadeiaIntegridadeService.verificarCadeia(null, 2026).get("verificada")).isEqualTo(false);

        // HTTP: só o Admin do Sistema (área Faturação).
        String admin = login(ADMIN_SISTEMA_EMAIL);
        mockMvc.perform(get("/api/faturacao/integridade?serie=" + SERIE).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serie").value(SERIE))
                .andExpect(jsonPath("$.integra").value(false));
        mockMvc.perform(get("/api/faturacao/integridade").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verificada").value(false));
        mockMvc.perform(get("/api/faturacao/integridade?serie=" + SERIE).header("Authorization", "Bearer " + login(FORNECEDOR_EMAIL)))
                .andExpect(status().isForbidden());
    }

    // --- SAF-T (AO) -----------------------------------------------------------------------

    @Test
    void saftDeUmaEmpresaFornecedora() throws Exception {
        String fornecedorId = companyId("AO-FOR-0001");
        String compradorId = companyId("AO-CLI-0001");

        // Exige um período, recusa datas invertidas e exige a empresa.
        assertThatThrownBy(() -> saftService.gerar("ontem", "hoje", fornecedorId)).hasMessageContaining("AAAA-MM-DD");
        assertThatThrownBy(() -> saftService.gerar("2030-01-01", "2020-01-01", fornecedorId)).hasMessageContaining("posterior");
        assertThatThrownBy(() -> saftService.gerar("2020-01-01", "2035-12-31", null)).hasMessageContaining("supplierCompanyId");

        // XML bem formado com o cabeçalho e os totais; CompanyID do FORNECEDOR.
        String f1 = emitirDoFornecedor(1000);
        emitirDoFornecedor(2000);
        SaftService.Resultado r = saftService.gerar("2020-01-01", "2035-12-31", fornecedorId);
        String xml = r.xml();
        assertThat(xml).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        assertThat(xml).contains("<AuditFile").contains("</AuditFile>").contains("<SalesInvoices>");
        for (String tag : List.of("Header", "MasterFiles", "SourceDocuments", "SalesInvoices", "Line", "Tax")) {
            assertThat(contar(xml, "<" + tag + ">")).isEqualTo(contar(xml, "</" + tag + ">"));
        }
        assertThat((Integer) r.resumo().get("documentos")).isGreaterThanOrEqualTo(2);
        assertThat(xml).contains("<CompanyID>AO-FOR-0001</CompanyID>");
        @SuppressWarnings("unchecked") Map<String, Object> fornecedor = (Map<String, Object>) r.resumo().get("fornecedor");
        assertThat(fornecedor.get("id")).isEqualTo(fornecedorId);
        @SuppressWarnings("unchecked") List<String> porConfigurar = (List<String>) r.resumo().get("porConfigurar");
        assertThat(porConfigurar).contains("KIXIMA_CERTIFICADO_AGT");
        assertThat(r.resumo().get("semSerieCertificada")).isInstanceOf(Integer.class);

        // As faturas de UM fornecedor não aparecem no SAF-T de outro.
        String outro = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO companies (id, name, tax_id, type, contact_email, status, created_at, updated_at) VALUES (?, 'Fornecedora Isolada Teste', ?, 'FORNECEDOR'::\"CompanyType\", 'iso@test.co.ao', 'APROVADA'::\"CompanyStatus\", now(), now())",
                outro, "AO-TEST-ISO-" + outro.substring(0, 6));
        Map<String, Object> faturaOutro = emitir(500, criarPO(outro));
        String numeroOutro = numero(faturaOutro);
        assertThat(saftService.gerar("2020-01-01", "2035-12-31", fornecedorId).xml()).doesNotContain(numeroOutro);
        SaftService.Resultado rOutro = saftService.gerar("2020-01-01", "2035-12-31", outro);
        assertThat(rOutro.xml()).contains(numeroOutro);

        // Escapa caracteres que partiriam o XML.
        jdbcTemplate.update("UPDATE companies SET name = 'Sonangol & Filhos <Lda>' WHERE id = ?", compradorId);
        entityManager.clear();
        xml = saftService.gerar("2020-01-01", "2035-12-31", fornecedorId).xml();
        assertThat(xml).doesNotContain("Sonangol & Filhos <Lda>").contains("Sonangol &amp; Filhos &lt;Lda&gt;");

        // <Line> por fatura com código/descrição/imposto — e o InvoiceNo no formato oficial.
        Map<String, Object> comLinha = emitir(1140, criarPO(fornecedorId));
        linha((String) comLinha.get("id"), "SKU-SAFT-1", "Produto de teste SAF-T", 2, 500, 1000, 140, "NOR");
        xml = saftService.gerar("2020-01-01", "2035-12-31", fornecedorId).xml();
        assertThat(xml).contains("<ProductCode>SKU-SAFT-1</ProductCode>").contains("<ProductDescription>Produto de teste SAF-T</ProductDescription>")
                .contains("<Quantity>2</Quantity>").contains("<UnitPrice>500.00</UnitPrice>").contains("<TaxCode>NOR</TaxCode>").contains("<TaxAmount>140.00</TaxAmount>");
        String numeroComLinha = numero(comLinha);
        assertThat(xml).contains("<InvoiceNo>" + numeroComLinha + "</InvoiceNo>");
        assertThat(numeroComLinha).matches("^.+\\.\\d{4}/\\d{7}$");

        // Nota de crédito entra como NC, referenciando a fatura original; status A só quando totalmente creditada.
        Map<String, Object> parcial = emitir(1000, criarPO(fornecedorId));
        Map<String, Object> nota = emitirNotaCredito((String) parcial.get("id"), 300, "Devolução parcial");
        Map<String, Object> total = emitir(2000, criarPO(fornecedorId));
        emitirNotaCredito((String) total.get("id"), 2000, "Anulação");
        xml = saftService.gerar("2020-01-01", "2035-12-31", fornecedorId).xml();
        assertThat(contar(xml, "InvoiceType>NC<")).isGreaterThanOrEqualTo(2);
        assertThat(xml).contains(numero(nota)).contains("Devolução parcial").contains("<Reference>" + numero(parcial) + "</Reference>");
        assertThat(statusDe(xml, numero(parcial))).isEqualTo("N");
        assertThat(statusDe(xml, numero(total))).isEqualTo("A");
        Matcher entradas = Pattern.compile("<NumberOfEntries>(\\d+)</NumberOfEntries>").matcher(xml);
        assertThat(entradas.find()).isTrue();
        assertThat(Integer.parseInt(entradas.group(1))).isGreaterThanOrEqualTo(7); // 5 faturas + 2 notas

        // HTTP: cabeçalhos e ficheiro; o Admin tem de indicar a empresa; o comprador não entra.
        String admin = login(ADMIN_SISTEMA_EMAIL);
        mockMvc.perform(get("/api/faturacao/saft?de=2020-01-01&ate=2035-12-31&supplierCompanyId=" + fornecedorId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("application/xml")))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"SAFT-AO-2020-01-01-a-2035-12-31.xml\""))
                .andExpect(header().exists("X-Kixima-Documentos"));
        mockMvc.perform(get("/api/faturacao/saft?de=2020-01-01&ate=2035-12-31").header("Authorization", "Bearer " + admin))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/faturacao/saft/resumo?de=ontem&ate=hoje").header("Authorization", "Bearer " + login(FORNECEDOR_EMAIL)))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/faturacao/saft/resumo?de=2020-01-01&ate=2035-12-31").header("Authorization", "Bearer " + login(FORNECEDOR_EMAIL)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fornecedor.id").value(fornecedorId));
        mockMvc.perform(get("/api/faturacao/saft?de=2020-01-01&ate=2035-12-31").header("Authorization", "Bearer " + login(COMPRADOR_EMAIL)))
                .andExpect(status().isForbidden());
        assertThat(f1).isNotBlank();
    }

    private static String statusDe(String xml, String numero) {
        int inicio = xml.indexOf("<InvoiceNo>" + numero + "</InvoiceNo>");
        assertThat(inicio).isGreaterThanOrEqualTo(0);
        String bloco = xml.substring(inicio, xml.indexOf("</Invoice>", inicio));
        Matcher m = Pattern.compile("<InvoiceStatus>(.)</InvoiceStatus>").matcher(bloco);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    // --- Métricas -------------------------------------------------------------------------

    @Test
    void metricasMedianaTaxasNulasEResumo() throws Exception {
        assertThat(MetricasService.mediana(List.of(1d, 1d, 1d, 1d, 1000d))).isEqualTo(1d);
        assertThat(MetricasService.mediana(List.of(1d, 2d, 3d, 4d))).isEqualTo(2.5);
        assertThat(MetricasService.mediana(List.of())).isNull();

        // Períodos sem atividade: as taxas ficam nulas em vez de darem 0%; o volume dá zero, que aí é o número certo.
        MetricasService.Janela antigo = new MetricasService.Janela(Instant.parse("2001-01-01T00:00:00Z"), Instant.parse("2001-12-31T00:00:00Z"));
        Map<String, Object> cot = metricasService.conversaoDeCotacoes(antigo);
        assertThat(cot.get("pedidas")).isEqualTo(0L);
        assertThat(cot.get("taxaDeResposta")).isNull();
        assertThat(cot.get("taxaDeFecho")).isNull();
        Map<String, Object> vol = metricasService.volumeTransacionado(antigo);
        assertThat(vol.get("total")).isEqualTo(0d);
        assertThat(vol.get("ordens")).isEqualTo(0L);
        assertThat(vol.get("ticketMedio")).isEqualTo(0d);

        // O resumo responde às três perguntas, com o período declarado; um número de dias absurdo não rebenta.
        Map<String, Object> r = metricasService.resumo("30");
        @SuppressWarnings("unchecked") Map<String, Object> periodo = (Map<String, Object>) r.get("periodo");
        assertThat(periodo.get("dias")).isEqualTo(30);
        assertThat(((Map<?, ?>) r.get("volume")).containsKey("total")).isTrue();
        assertThat(((Map<?, ?>) r.get("cotacoes")).containsKey("taxaDeFecho")).isTrue();
        assertThat(((Map<?, ?>) r.get("tempoAteConfirmacao")).containsKey("medianaHoras")).isTrue();
        assertThat(((Map<?, ?>) r.get("tempoAteConfirmacao")).get("porCanal")).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) r.get("conciliacao")).containsKey("taxaAutomatica")).isTrue();
        for (String lixo : new String[]{"abc", "-5", "0", null}) {
            @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) metricasService.resumo(lixo).get("periodo");
            assertThat(p.get("dias")).isEqualTo(30);
        }

        // HTTP: só o Admin do Sistema (área Faturação).
        mockMvc.perform(get("/api/faturacao/metricas?dias=3650").header("Authorization", "Bearer " + login(ADMIN_SISTEMA_EMAIL)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.periodo.dias").value(3650)).andExpect(jsonPath("$.tempoAteConfirmacao.porCanal").isMap());
        mockMvc.perform(get("/api/faturacao/metricas").header("Authorization", "Bearer " + login(COMPRADOR_EMAIL))).andExpect(status().isForbidden());
    }
}
