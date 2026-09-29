package ao.kixima.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Espelha tests/catalog.test.js (ficha completa com media),
 * tests/catalog-fornecedor-melhorias.test.js (editar media de um item
 * publicado + auditoria) e tests/catalog-import.test.js (carregamento em
 * massa por Excel) — a escrita do catálogo portada em D.2.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CatalogWriteControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String FORNECEDOR_EMAIL = "fornecedor@kianda.co.ao";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
    private static final byte[] PDF = "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF".getBytes(StandardCharsets.UTF_8);
    private static final List<String> HEADER = List.of("Categoria", "Produto/Serviço", "Descrição", "Tipo", "UOM", "Código UNSPSC",
            "Título Oficial UNSPSC", "Segmento UNSPSC", "Família UNSPSC", "País de Origem", "Preço");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CatalogImportService importService;

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

    private JsonNode json(String body) throws Exception {
        return objectMapper.readTree(body);
    }

    private String supplierId() {
        return jdbcTemplate.queryForObject("SELECT company_id FROM users WHERE email = ?", String.class, FORNECEDOR_EMAIL);
    }

    private void porNoPlano(String plano) {
        entityManager.flush();
        jdbcTemplate.update("UPDATE companies SET plan = ?::\"CompanyPlan\" WHERE id = ?", plano, supplierId());
        entityManager.clear();
    }

    private static MockMultipartFile png(String campo, String nome) {
        return new MockMultipartFile(campo, nome, "image/png", PNG);
    }

    private static MockMultipartFile pdf(String campo, String nome) {
        return new MockMultipartFile(campo, nome, "application/pdf", PDF);
    }

    private JsonNode obter(String token, String productId) throws Exception {
        return json(mockMvc.perform(get("/api/catalog/" + productId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private int auditCount(String action, String entityId) {
        entityManager.flush();
        return jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = ? AND entity_id = ?", Integer.class, action, entityId);
    }

    // --- catalog.test.js ------------------------------------------------------------

    @Test
    void criaProdutoComFichaCompletaImagemPrincipalGaleriaEDocumentos() throws Exception {
        String token = login(FORNECEDOR_EMAIL);
        MockMultipartHttpServletRequestBuilder b = multipart("/api/catalog")
                .file(png("mainImage", "principal.png")).file(png("gallery", "g1.png")).file(png("gallery", "g2.png"))
                .file(pdf("FICHA_TECNICA", "ficha.pdf")).file(pdf("CERTIFICADO", "cert1.pdf")).file(pdf("CERTIFICADO", "cert2.pdf"));
        b.header("Authorization", "Bearer " + token);
        Map<String, String> campos = new LinkedHashMap<>();
        campos.put("name", "Válvula de Esfera 6\" API 6D");
        campos.put("sku", "VLV-6-API6D");
        campos.put("manufacturerCode", "CAM-6D-600");
        campos.put("category", "Válvulas");
        campos.put("subcategory", "Esfera");
        campos.put("brand", "Cameron");
        campos.put("manufacturer", "Cameron International");
        campos.put("model", "T31");
        campos.put("countryOfOrigin", "EUA");
        campos.put("description", "Válvula de esfera flutuante para óleo e gás.");
        campos.put("fullDescription", "Corpo em aço carbono, trim inox, classe 600.");
        campos.put("applications", "Linhas de processo, manifolds.");
        campos.put("benefits", "Vedação bidirecional, baixo torque.");
        campos.put("keywords", "valvula, esfera, api 6d, 600");
        campos.put("material", "Aço carbono A216 WCB");
        campos.put("weight", "85 kg");
        campos.put("pressure", "600# (100 bar)");
        campos.put("temperature", "-29°C a 120°C");
        campos.put("measurementUnit", "unidade");
        campos.put("unitPrice", "1250000");
        campos.put("promoPrice", "1120000");
        campos.put("currency", "AOA");
        campos.put("minQuantity", "1");
        campos.put("maxQuantity", "50");
        campos.put("stockQuantity", "12");
        campos.put("warehouse", "Luanda — Zona Industrial");
        campos.put("leadTimeDays", "15");
        campos.put("availability", "Em stock");
        campos.put("minStock", "3");
        campos.forEach(b::param);

        var res = mockMvc.perform(b).andExpect(status().isCreated()).andReturn();
        JsonNode body = json(res.getResponse().getContentAsString());
        String createdId = body.get("id").asText();
        assertThat(body.get("sku").asText()).isEqualTo("VLV-6-API6D");
        // Decimal do Prisma sai como texto (JacksonDecimalConfig), tal como no Node.
        assertThat(body.get("promoPrice").isTextual()).isTrue();
        assertThat(new java.math.BigDecimal(body.get("promoPrice").asText()).intValue()).isEqualTo(1120000);
        assertThat(body.get("stockQuantity").asInt()).isEqualTo(12);
        assertThat(body.get("imageUrl").asText()).isNotBlank(); // principal reflete no marketplace
        assertThat(body.get("images").size()).isEqualTo(3); // 1 principal + 2 galeria
        int principais = 0;
        for (JsonNode i : body.get("images")) if (i.get("isPrimary").asBoolean()) principais++;
        assertThat(principais).isEqualTo(1);
        assertThat(body.get("documents").size()).isEqualTo(3); // 1 ficha técnica + 2 certificados
        assertThat(body.has("supplier")).isFalse();
        assertThat(auditCount("CATALOGO_PRODUTO_CRIADO", createdId)).isEqualTo(1);

        // getProduct traz imagens e documentos.
        JsonNode detail = obter(token, createdId);
        assertThat(detail.get("images").size()).isEqualTo(3);
        boolean temFicha = false;
        for (JsonNode d : detail.get("documents")) if ("FICHA_TECNICA".equals(d.get("type").asText())) temFicha = true;
        assertThat(temFicha).isTrue();

        // Rejeita produto sem nome (422); o comprador não publica (403).
        mockMvc.perform(multipart("/api/catalog").header("Authorization", "Bearer " + token).param("category", "Válvulas").param("unitPrice", "1000"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(multipart("/api/catalog").header("Authorization", "Bearer " + login(COMPRADOR_EMAIL))
                        .param("name", "Intruso").param("category", "Válvulas").param("unitPrice", "1000"))
                .andExpect(status().isForbidden());

        // Sem imagem/documentos também cria (campos de media são opcionais) — e por JSON, como o express.json permite.
        var semMedia = mockMvc.perform(post("/api/catalog").header("Authorization", "Bearer " + token).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", "Serviço de Inspeção NDT", "category", "Inspeção & Ensaios", "unitPrice", 500000))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode servico = json(semMedia.getResponse().getContentAsString());
        assertThat(servico.get("images").size()).isEqualTo(0);
        assertThat(servico.get("currency").asText()).isEqualTo("AOA");
        assertThat(servico.get("slug").asText()).startsWith("servico-de-inspecao-ndt-");

        // Desativar regista CATALOGO_PRODUTO_REMOVIDO e tira o item do catálogo.
        mockMvc.perform(delete("/api/catalog/" + servico.get("id").asText()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        assertThat(auditCount("CATALOGO_PRODUTO_REMOVIDO", servico.get("id").asText())).isEqualTo(1);
    }

    // --- catalog-fornecedor-melhorias.test.js (media) -------------------------------------

    @Test
    void editarProdutoPublicadoMedia() throws Exception {
        String token = login(FORNECEDOR_EMAIL);
        var criado = mockMvc.perform(multipart("/api/catalog")
                        .file(png("mainImage", "principal.png")).file(png("gallery", "g1.png")).file(pdf("FICHA_TECNICA", "ficha.pdf"))
                        .header("Authorization", "Bearer " + token)
                        .param("name", "Item para editar media").param("category", "Materiais").param("unitPrice", "1000"))
                .andExpect(status().isCreated()).andReturn();
        String productId = json(criado.getResponse().getContentAsString()).get("id").asText();

        // PUT /:id atualiza campos de texto/preço; auditoria com os campos alterados.
        mockMvc.perform(put("/api/catalog/" + productId).header("Authorization", "Bearer " + token).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", "Item editado", "unitPrice", 2000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Item editado"))
                .andExpect(jsonPath("$.unitPrice").value(2000));
        assertThat(auditCount("CATALOGO_PRODUTO_ATUALIZADO", productId)).isEqualTo(1);
        entityManager.flush();
        String detalhe = jdbcTemplate.queryForObject("SELECT detail::text FROM audit_logs WHERE action = 'CATALOGO_PRODUTO_ATUALIZADO' AND entity_id = ?", String.class, productId);
        assertThat(detalhe).contains("camposAlterados").contains("name").contains("unitPrice");
        // Campo inválido → 422; outro fornecedor não edita → 403 (comprador nem entra: 403 do papel).
        mockMvc.perform(put("/api/catalog/" + productId).header("Authorization", "Bearer " + token).contentType("application/json")
                        .content("{\"unitPrice\":\"abc\"}"))
                .andExpect(status().isUnprocessableEntity());

        // POST /:id/media acrescenta à galeria e a documentos existentes.
        mockMvc.perform(multipart("/api/catalog/" + productId + "/media")
                        .file(png("gallery", "g2.png")).file(pdf("CERTIFICADO", "cert.pdf")).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images.length()").value(3))
                .andExpect(jsonPath("$.documents.length()").value(2));

        // Respeita o limite do plano (CORE = 3 documentos por item): o terceiro cabe, o quarto é recusado.
        mockMvc.perform(multipart("/api/catalog/" + productId + "/media").file(pdf("CATALOGO", "cat.pdf")).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.documents.length()").value(3));
        mockMvc.perform(multipart("/api/catalog/" + productId + "/media").file(pdf("DATASHEET", "ds.pdf")).header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("PLANO_INSUFICIENTE"));
        assertThat(obter(token, productId).get("documents").size()).isEqualTo(3); // nada foi acrescentado

        // DELETE /:id/images/:imageId remove e promove a próxima principal.
        JsonNode antes = obter(token, productId);
        JsonNode principal = null;
        JsonNode outra = null;
        for (JsonNode i : antes.get("images")) {
            if (i.get("isPrimary").asBoolean() && principal == null) principal = i;
            else if (!i.get("isPrimary").asBoolean() && outra == null) outra = i;
        }
        assertThat(principal).isNotNull();
        assertThat(outra).isNotNull();
        mockMvc.perform(delete("/api/catalog/" + productId + "/images/" + principal.get("id").asText()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.removida").value(true));
        JsonNode depois = obter(token, productId);
        JsonNode promovida = null;
        for (JsonNode i : depois.get("images")) {
            assertThat(i.get("id").asText()).isNotEqualTo(principal.get("id").asText());
            if (i.get("id").asText().equals(outra.get("id").asText())) promovida = i;
        }
        assertThat(promovida).isNotNull();
        assertThat(promovida.get("isPrimary").asBoolean()).isTrue();
        assertThat(depois.get("imageUrl").asText()).isEqualTo(outra.get("url").asText());
        mockMvc.perform(delete("/api/catalog/" + productId + "/images/inexistente").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        // DELETE /:id/documents/:docId remove.
        String docId = depois.get("documents").get(0).get("id").asText();
        mockMvc.perform(delete("/api/catalog/" + productId + "/documents/" + docId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.removido").value(true));
        JsonNode semDoc = obter(token, productId);
        for (JsonNode d : semDoc.get("documents")) assertThat(d.get("id").asText()).isNotEqualTo(docId);
        assertThat(semDoc.get("documents").size()).isEqualTo(2);

        // POST /:id/image (trocar foto de capa) mantém a galeria em sincronia.
        int totalAntes = semDoc.get("images").size();
        mockMvc.perform(multipart("/api/catalog/" + productId + "/image").file(png("image", "nova-capa.png")).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        JsonNode comCapa = obter(token, productId);
        int principais = 0;
        String urlPrincipal = null;
        for (JsonNode i : comCapa.get("images")) if (i.get("isPrimary").asBoolean()) {
            principais++;
            urlPrincipal = i.get("url").asText();
        }
        assertThat(principais).isEqualTo(1);
        assertThat(urlPrincipal).isEqualTo(comCapa.get("imageUrl").asText());
        assertThat(comCapa.get("images").size()).isEqualTo(totalAntes + 1); // a foto antiga passou para a galeria
        // Sem ficheiro → 400 NO_FILE; formato errado → 422 com a dica HEIC.
        mockMvc.perform(multipart("/api/catalog/" + productId + "/image").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("NO_FILE"));
        mockMvc.perform(multipart("/api/catalog/" + productId + "/image")
                        .file(new MockMultipartFile("image", "foto.heic", "image/heic", PNG)).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("HEIC")));
    }

    // --- catalog-import.test.js --------------------------------------------------------------

    private static byte[] buildXlsx(List<List<String>> rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet ws = wb.createSheet("Catálogo");
            for (int r = 0; r < rows.size(); r++) {
                Row row = ws.createRow(r);
                for (int c = 0; c < rows.get(r).size(); c++) row.createCell(c).setCellValue(rows.get(r).get(c));
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** Injeta um drawing.xml + media mínimos no .xlsx (o POI, tal como o SheetJS, não escreve imagens embebidas). */
    private static byte[] comImagensFalsas(byte[] xlsx, int quantidade) throws Exception {
        StringBuilder anchors = new StringBuilder();
        StringBuilder rels = new StringBuilder();
        for (int i = 1; i <= quantidade; i++) {
            anchors.append("<xdr:oneCellAnchor><xdr:from><xdr:col>0</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>").append(i)
                    .append("</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from><xdr:pic><xdr:blipFill><a:blip r:embed=\"rId").append(i)
                    .append("\"/></xdr:blipFill></xdr:pic></xdr:oneCellAnchor>");
            rels.append("<Relationship Id=\"rId").append(i).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"../media/image")
                    .append(i).append(".png\"/>");
        }
        String drawingXml = "<?xml version=\"1.0\"?><xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" "
                + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + anchors + "</xdr:wsDr>";
        String relsXml = "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" + rels + "</Relationships>";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(xlsx)); ZipOutputStream zip = new ZipOutputStream(out)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                byte[] dados = in.readAllBytes();
                if ("[Content_Types].xml".equals(e.getName())) {
                    String tipos = new String(dados, StandardCharsets.UTF_8).replace("</Types>",
                            "<Default Extension=\"png\" ContentType=\"image/png\"/>"
                                    + "<Override PartName=\"/xl/drawings/drawing1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/></Types>");
                    dados = tipos.getBytes(StandardCharsets.UTF_8);
                }
                zip.putNextEntry(new ZipEntry(e.getName()));
                zip.write(dados);
                zip.closeEntry();
            }
            for (int i = 1; i <= quantidade; i++) {
                zip.putNextEntry(new ZipEntry("xl/media/image" + i + ".png"));
                zip.write(PNG);
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("xl/drawings/drawing1.xml"));
            zip.write(drawingXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("xl/drawings/_rels/drawing1.xml.rels"));
            zip.write(relsXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private static List<String> linha(String... v) {
        return List.of(v);
    }

    private Map<String, Object> importar(byte[] xlsx) {
        return importService.importCatalog(xlsx, supplierId());
    }

    private Map<String, Object> produto(String nome) {
        entityManager.flush();
        return jdbcTemplate.queryForMap("SELECT kind::text AS kind, unspsc_code, unspsc_segment, unspsc_family, country_of_origin, unit_price, "
                + "currency, stock_quantity, city, province FROM products WHERE supplier_id = ? AND name = ?", supplierId(), nome);
    }

    private int contar(String nome) {
        entityManager.flush();
        return jdbcTemplate.queryForObject("SELECT count(*) FROM products WHERE supplier_id = ? AND name = ?", Integer.class, supplierId(), nome);
    }

    private static MockMultipartFile xlsx(byte[] conteudo) {
        return new MockMultipartFile("file", "catalogo.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", conteudo);
    }

    /**
     * O carregamento em massa é do plano Pro e o seed deixa a Kianda em CORE —
     * o `beforeAll` do Node põe a empresa em Pro (e repõe no fim); aqui cada
     * cenário chama isto e o {@code @Transactional} da classe repõe tudo.
     */
    private void emPro() {
        porNoPlano("PRO");
    }

    // Um cenário por `test()` de catalog-import.test.js, pela mesma ordem.

    @Test
    void parsePriceInterpretaFormatosAoaENumeros() {
        assertThat(CatalogImportService.parsePrice("1.250.000,00 AOA")).isEqualTo(1250000d);
        assertThat(CatalogImportService.parsePrice("850000")).isEqualTo(850000d);
        assertThat(CatalogImportService.parsePrice(120000)).isEqualTo(120000d);
        assertThat(CatalogImportService.parsePrice("")).isNull();
        assertThat(CatalogImportService.parsePrice(null)).isNull();
    }

    @Test
    void importCatalogExigeOPlanoPro() throws Exception {
        // Fora do plano Pro (CORE, seed da Kianda) é recusado, e diz qual plano falta — a regra que o Node só assume no beforeAll.
        byte[] buf = buildXlsx(List.of(HEADER, linha("Segurança e EPI", "Capacete de teste", "Capacete", "Produto", "un", "46181503", "Helmet", "46 — X", "4618 — Y", "Angola", "30000")));
        mockMvc.perform(multipart("/api/catalog/import").file(xlsx(buf)).header("Authorization", "Bearer " + login(FORNECEDOR_EMAIL)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PLANO_INSUFICIENTE"))
                .andExpect(jsonPath("$.error.details.planoNecessario").value("PRO"));
    }

    @Test
    void criaProdutosEServicosComUnspscOrigemEPreco() throws Exception {
        emPro();
        byte[] buf = buildXlsx(List.of(HEADER,
                linha("Válvulas e Conexões", "Válvula de teste", "Válvula de esfera de teste", "Produto", "un", "40141607", "Ball valves", "40 — X", "4014 — Y", "EUA", "1.500.000,00 AOA"),
                linha("Inspeção, Testes e Certificação", "Inspeção de teste", "Serviço de inspeção", "Serviço", "serviço", "81141804", "Inspection", "81 — Z", "8114 — W", "Angola", "")));
        Map<String, Object> res = importar(buf);
        assertThat(res.get("total")).isEqualTo(2);
        assertThat((Integer) res.get("created") + (Integer) res.get("updated")).isEqualTo(2);
        assertThat((List<?>) res.get("errors")).isEmpty();

        Map<String, Object> prod = produto("Válvula de teste");
        assertThat(prod.get("kind")).isEqualTo("PRODUTO");
        assertThat(prod.get("unspsc_code")).isEqualTo("40141607");
        assertThat(prod.get("unspsc_segment")).isEqualTo("40");
        assertThat(prod.get("unspsc_family")).isEqualTo("4014");
        assertThat(prod.get("country_of_origin")).isEqualTo("EUA");
        assertThat(((java.math.BigDecimal) prod.get("unit_price")).intValue()).isEqualTo(1500000);
        assertThat(prod.get("currency")).isEqualTo("AOA");

        Map<String, Object> serv = produto("Inspeção de teste");
        assertThat(serv.get("kind")).isEqualTo("SERVICO");
        assertThat(serv.get("country_of_origin")).isEqualTo("Angola");
        // sem coluna de preço preenchida → preço estimado (> 0)
        assertThat(((java.math.BigDecimal) serv.get("unit_price")).intValue()).isGreaterThan(0);
        assertThat(serv.get("stock_quantity")).isNull(); // serviço não tem stock
    }

    @Test
    void eIdempotenteReimportarAtualizaNaoDuplica() throws Exception {
        emPro();
        byte[] buf = buildXlsx(List.of(HEADER, linha("Bombas e Compressores", "Bomba de teste", "Bomba", "Produto", "un", "40151503", "Pumps", "40 — X", "4015 — Y", "Angola", "900000")));
        importar(buf);
        Map<String, Object> r2 = importar(buf);
        assertThat(r2.get("created")).isEqualTo(0);
        assertThat(r2.get("updated")).isEqualTo(1);
        assertThat(contar("Bomba de teste")).isEqualTo(1);
    }

    @Test
    void rejeitaFicheiroSemAsColunasMinimas() throws Exception {
        emPro();
        byte[] buf = buildXlsx(List.of(linha("Coluna A", "Coluna B"), linha("x", "y")));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> importar(buf))
                .isInstanceOf(ao.kixima.common.error.BusinessRuleException.class)
                .hasMessageContaining("Categoria").hasMessageContaining("Produto");
    }

    @Test
    void endpointHttpSoFornecedorCompanyAdminImportam() throws Exception {
        emPro();
        String fornecedor = login(FORNECEDOR_EMAIL);
        byte[] buf = buildXlsx(List.of(HEADER, linha("Segurança e EPI", "Capacete de teste", "Capacete", "Produto", "un", "46181503", "Helmet", "46 — X", "4618 — Y", "Angola", "30000")));
        mockMvc.perform(multipart("/api/catalog/import").file(xlsx(buf)).header("Authorization", "Bearer " + fornecedor))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.total").value(1));
        mockMvc.perform(multipart("/api/catalog/import").file(xlsx(buf)).header("Authorization", "Bearer " + login(COMPRADOR_EMAIL)))
                .andExpect(status().isForbidden());

        // Sem ficheiro → 400 NO_FILE; formato errado → 422 (filtro do upload, como o multer do Node).
        mockMvc.perform(multipart("/api/catalog/import").header("Authorization", "Bearer " + fornecedor))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("NO_FILE"));
        mockMvc.perform(multipart("/api/catalog/import")
                        .file(new MockMultipartFile("file", "catalogo.csv", "text/csv", "a,b".getBytes(StandardCharsets.UTF_8))).header("Authorization", "Bearer " + fornecedor))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void contaPrecosEstimadosQuandoAColunaPrecoVemVazia() throws Exception {
        emPro();
        Map<String, Object> res = importar(buildXlsx(List.of(HEADER,
                linha("Válvulas e Conexões", "Válvula com preço de teste", "x", "Produto", "un", "", "", "", "", "", "700000"),
                linha("Válvulas e Conexões", "Válvula sem preço de teste", "x", "Produto", "un", "", "", "", "", "", ""))));
        assertThat(res.get("precosEstimados")).isEqualTo(1);
    }

    @Test
    void usaAsColunasStockECidadeQuandoPresentesContaPorOmissaoQuandoAusentes() throws Exception {
        emPro();
        List<String> headerComStockCidade = new ArrayList<>(HEADER);
        headerComStockCidade.add("Stock");
        headerComStockCidade.add("Cidade");
        Map<String, Object> res = importar(buildXlsx(List.of(headerComStockCidade,
                linha("Bombas e Compressores", "Bomba com stock de teste", "x", "Produto", "un", "", "", "", "", "", "500000", "17", "Cabinda"),
                linha("Bombas e Compressores", "Bomba sem stock de teste", "x", "Produto", "un", "", "", "", "", "", "500000", "", ""))));
        assertThat(res.get("stockPorOmissao")).isEqualTo(1);
        assertThat(res.get("localizacaoPorOmissao")).isEqualTo(1);

        Map<String, Object> comColuna = produto("Bomba com stock de teste");
        assertThat(comColuna.get("stock_quantity")).isEqualTo(17);
        assertThat(comColuna.get("city")).isEqualTo("Cabinda");
        assertThat(comColuna.get("province")).isEqualTo("Cabinda");

        Map<String, Object> semColuna = produto("Bomba sem stock de teste");
        assertThat(semColuna.get("stock_quantity")).isEqualTo(50);
        assertThat(semColuna.get("city")).isEqualTo("Luanda");
    }

    @Test
    void avisaQuandoONumeroDeFotosNaoBateComODeLinhasDeDados() throws Exception {
        emPro();
        byte[] base = buildXlsx(List.of(HEADER, linha("Elétrico, Iluminação e Automação", "Item com fotos desalinhadas", "x", "Produto", "un", "", "", "", "", "", "400000")));
        // 1 linha de dados, 2 "fotos" embebidas — desalinhado de propósito.
        Map<String, Object> res = importar(comImagensFalsas(base, 2));
        @SuppressWarnings("unchecked") List<String> avisos = (List<String>) res.get("warnings");
        assertThat(avisos).hasSize(1);
        assertThat(avisos.get(0)).contains("2 imagem").contains("1 linha");
    }

    @Test
    void naoAvisaQuandoONumeroDeFotosBateComODeLinhas() throws Exception {
        emPro();
        byte[] base = buildXlsx(List.of(HEADER, linha("Elétrico, Iluminação e Automação", "Item com fotos alinhadas", "x", "Produto", "un", "", "", "", "", "", "400000")));
        Map<String, Object> res = importar(comImagensFalsas(base, 1));
        assertThat((List<?>) res.get("warnings")).isEmpty();
        assertThat(res.get("withImages")).isEqualTo(1);
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT image_url FROM products WHERE supplier_id = ? AND name = 'Item com fotos alinhadas'", String.class, supplierId()))
                .startsWith("/api/uploads/");
    }

    @Test
    void processaMuitasLinhasIndependentesSemSeAtropelarem() throws Exception {
        emPro();
        int n = 150;
        List<List<String>> rows = new ArrayList<>();
        rows.add(HEADER);
        for (int k = 0; k < n; k++) {
            rows.add(linha("Ferramentas e Equipamento de Oficina", "Item em massa " + k, "x", "Produto", "un", "MASSA-" + k, "", "", "", "", String.valueOf(100000 + k)));
        }
        Map<String, Object> res = importar(buildXlsx(rows));
        assertThat(res.get("total")).isEqualTo(n);
        assertThat(res.get("created")).isEqualTo(n);
        assertThat((List<?>) res.get("errors")).isEmpty();
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM products WHERE supplier_id = ? AND name LIKE 'Item em massa %'", Integer.class, supplierId())).isEqualTo(n);
    }

    @Test
    void duasLinhasComOMesmoProdutoNoMesmoFicheiroAPrimeiraCriaASegundaFicaComoErro() throws Exception {
        emPro();
        Map<String, Object> res = importar(buildXlsx(List.of(HEADER,
                linha("Ferramentas e Equipamento de Oficina", "Item duplicado no ficheiro", "x", "Produto", "un", "DUP-001", "", "", "", "", "111111"),
                linha("Ferramentas e Equipamento de Oficina", "Item duplicado no ficheiro", "x", "Produto", "un", "DUP-001", "", "", "", "", "222222"))));
        assertThat(res.get("created")).isEqualTo(1);
        assertThat((List<?>) res.get("errors")).hasSize(1);
        assertThat(contar("Item duplicado no ficheiro")).isEqualTo(1);
        // A primeira ocorrência é a que ficou — o segundo preço não sobrescreveu em silêncio.
        assertThat(((java.math.BigDecimal) produto("Item duplicado no ficheiro").get("unit_price")).intValue()).isEqualTo(111111);
    }
}
