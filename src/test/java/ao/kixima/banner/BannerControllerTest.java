package ao.kixima.banner;

import ao.kixima.testsupport.Contas;
import ao.kixima.testsupport.Contas.Persona;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato dos banners da homepage (docs/api-banners.md do frontend): permissões (só ADMIN_SISTEMA com a área
 * «operacoes», ou Super Admin), validação por campo, ordem 1..n, upload de imagem, montra pública e auditoria.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class BannerControllerTest {

    private static final String PASSWORD = Contas.senha();
    private static final String ADMIN_EMAIL = Contas.email(Persona.ADMIN_SISTEMA);
    private static final String COMPRADOR_EMAIL = Contas.email(Persona.COMPRADOR);
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;
    @Autowired private PasswordEncoder passwordEncoder;

    // --- apoio ---------------------------------------------------------------------------------------------------

    private String login(String email, String senha) throws Exception {
        var res = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", senha))))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    private String admin() throws Exception {
        return login(ADMIN_EMAIL, PASSWORD);
    }

    /** O seed só tem um ADMIN_SISTEMA (Super Admin); um assessor restrito a uma área é criado como fixture. */
    private String assessor(String... areas) throws Exception {
        String email = Contas.emailUnico("banners.assessor");
        jdbcTemplate.update("INSERT INTO users (id, name, email, password_hash, role, admin_areas, active, created_at, updated_at) "
                        + "VALUES (?, 'Assessor (teste)', ?, ?, 'ADMIN_SISTEMA'::\"PersonaRole\", ?, true, now(), now())",
                UUID.randomUUID().toString(), email, passwordEncoder.encode(PASSWORD), areas);
        entityManager.clear();
        return login(email, PASSWORD);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private JsonNode json(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    private Map<String, Object> dados(String titulo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("etiqueta", "Teste");
        m.put("titulo", titulo);
        m.put("texto", "Texto do banner de teste.");
        m.put("cta", "Explorar");
        m.put("href", "/produtos");
        m.put("ativo", false);
        return m;
    }

    private JsonNode criar(String token, Map<String, Object> corpo, int esperado) throws Exception {
        return json(mockMvc.perform(post("/api/admin/banners").header("Authorization", bearer(token))
                        .contentType("application/json").content(objectMapper.writeValueAsString(corpo)))
                .andExpect(status().is(esperado)).andReturn());
    }

    private JsonNode criarValido(String token, String titulo) throws Exception {
        return criar(token, dados(titulo), 200);
    }

    private JsonNode imagem(String token, String id, MockMultipartFile f, int esperado) throws Exception {
        return json(mockMvc.perform(multipart("/api/admin/banners/" + id + "/imagem").file(f).header("Authorization", bearer(token)))
                .andExpect(status().is(esperado)).andReturn());
    }

    private static MockMultipartFile png() {
        return new MockMultipartFile("image", "banner.png", "image/png", PNG);
    }

    private JsonNode lista(String token) throws Exception {
        return json(mockMvc.perform(get("/api/admin/banners").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode publicos() throws Exception {
        return json(mockMvc.perform(get("/api/public/banners")).andExpect(status().isOk()).andReturn());
    }

    private static boolean contem(JsonNode array, String id) {
        for (JsonNode n : array) if (n.get("id").asText().equals(id)) return true;
        return false;
    }

    private int auditoria(String acao, String entityId) {
        entityManager.flush();
        return jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = ? AND entity_id = ?", Integer.class, acao, entityId);
    }

    // --- permissões ----------------------------------------------------------------------------------------------

    @Test
    void gestaoExigeSessaoEPapelAdminSistemaComAAreaOperacoes() throws Exception {
        mockMvc.perform(get("/api/admin/banners")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/banners").header("Authorization", bearer(login(COMPRADOR_EMAIL, PASSWORD))))
                .andExpect(status().isForbidden());

        String soFinanceiro = assessor("financeiro");
        mockMvc.perform(get("/api/admin/banners").header("Authorization", bearer(soFinanceiro))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/banners").header("Authorization", bearer(soFinanceiro))
                .contentType("application/json").content(objectMapper.writeValueAsString(dados("X")))).andExpect(status().isForbidden());

        String operacoes = assessor("operacoes");
        mockMvc.perform(get("/api/admin/banners").header("Authorization", bearer(operacoes))).andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/banners").header("Authorization", bearer(admin()))).andExpect(status().isOk()); // Super Admin
    }

    @Test
    void montraPublicaNaoPrecisaDeSessao() throws Exception {
        mockMvc.perform(get("/api/public/banners")).andExpect(status().isOk());
    }

    // --- criar e validar -----------------------------------------------------------------------------------------

    @Test
    void criaNoFimDaOrdemInactivoESemImagem() throws Exception {
        String t = admin();
        int antes = lista(t).size();
        JsonNode b = criarValido(t, "Novo banner");
        assertThat(b.get("id").asText()).isNotBlank();
        assertThat(b.get("ativo").asBoolean()).isFalse();
        assertThat(b.get("imagem").asText()).isEmpty();
        assertThat(b.get("ordem").asInt()).isEqualTo(antes + 1);
        assertThat(b.has("cta2")).isFalse(); // opcional ausente não vai no JSON
        assertThat(contem(lista(t), b.get("id").asText())).isTrue();
        assertThat(auditoria("BANNER_CRIADO", b.get("id").asText())).isEqualTo(1);
    }

    @Test
    void recusaCamposInvalidosComDetalhesPorCampo() throws Exception {
        String t = admin();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("titulo", "x".repeat(81));
        m.put("texto", "");
        m.put("href", "//evil.example");
        m.put("cta2", "Segunda");
        m.put("etiqueta", "e".repeat(41));
        m.put("ativo", true);
        JsonNode erro = criar(t, m, 422).get("error");
        assertThat(erro.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        JsonNode d = erro.get("details");
        assertThat(d.get("titulo").asText()).isEqualTo("Máximo 80 caracteres.");
        assertThat(d.get("texto").asText()).isEqualTo("Obrigatório.");
        assertThat(d.get("cta").asText()).isEqualTo("Obrigatório.");
        assertThat(d.get("href").asText()).contains("caminho interno");
        assertThat(d.get("href2").asText()).contains("segunda chamada");
        assertThat(d.get("etiqueta").asText()).isEqualTo("Máximo 40 caracteres.");
        assertThat(d.get("ativo").asText()).contains("depois de ter imagem");
    }

    @Test
    void ligacoesAceitesSaoCaminhoInternoOuHttps() {
        assertThat(BannerService.linkValido("/produtos?promo=true")).isTrue();
        assertThat(BannerService.linkValido("/categorias/Tubagem e Válvulas")).isTrue();
        assertThat(BannerService.linkValido("https://exemplo.ao/promo")).isTrue();
        assertThat(BannerService.linkValido("//evil.example")).isFalse();
        assertThat(BannerService.linkValido("http://exemplo.ao")).isFalse();
        assertThat(BannerService.linkValido("javascript:alert(1)")).isFalse();
        assertThat(BannerService.linkValido("/com\nquebra")).isFalse();
    }

    @Test
    void tipoErradoNumCampoViraErroDoCampoENaoUm500() throws Exception {
        Map<String, Object> m = dados("Ok");
        m.put("titulo", 123);
        m.put("ativo", "sim");
        JsonNode d = criar(admin(), m, 422).get("error").get("details");
        assertThat(d.get("titulo").asText()).isEqualTo("Tem de ser texto.");
        assertThat(d.get("ativo").asText()).isEqualTo("Tem de ser verdadeiro ou falso.");
    }

    // --- actualizar, imagem e publicação -------------------------------------------------------------------------

    @Test
    void actualizaSoOsCamposEnviadosELimpaOsOpcionaisEmBranco() throws Exception {
        String t = admin();
        Map<String, Object> m = dados("Original");
        m.put("cta2", "Segunda");
        m.put("href2", "/planos");
        String id = criar(t, m, 200).get("id").asText();

        JsonNode b = json(mockMvc.perform(put("/api/admin/banners/" + id).header("Authorization", bearer(t))
                        .contentType("application/json").content("{\"titulo\":\"  Título novo  \",\"cta2\":\"\",\"href2\":\"\"}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(b.get("titulo").asText()).isEqualTo("Título novo"); // aparado
        assertThat(b.get("texto").asText()).isEqualTo("Texto do banner de teste."); // intacto
        assertThat(b.has("cta2")).isFalse();
        assertThat(b.has("href2")).isFalse();
        assertThat(b.get("atualizadoEm").isNull()).isFalse();
        assertThat(auditoria("BANNER_ATUALIZADO", id)).isEqualTo(1);

        // O estado final é validado: não se pode ficar com a segunda chamada sem ligação.
        mockMvc.perform(put("/api/admin/banners/" + id).header("Authorization", bearer(t)).contentType("application/json")
                        .content("{\"cta2\":\"Outra\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.details.href2").exists());
    }

    @Test
    void umBannerSoSePublicaDepoisDeTerImagemEAImagemEPublica() throws Exception {
        String t = admin();
        String id = criarValido(t, "Com imagem").get("id").asText();

        mockMvc.perform(put("/api/admin/banners/" + id).header("Authorization", bearer(t)).contentType("application/json")
                        .content("{\"ativo\":true}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.details.ativo").exists());
        assertThat(contem(publicos(), id)).isFalse();

        String url = imagem(t, id, png(), 200).get("imagem").asText();
        assertThat(url).startsWith("/api/uploads/");
        assertThat(contem(publicos(), id)).isFalse(); // com imagem mas ainda inactivo

        JsonNode ativo = json(mockMvc.perform(put("/api/admin/banners/" + id).header("Authorization", bearer(t))
                        .contentType("application/json").content("{\"ativo\":true}")).andExpect(status().isOk()).andReturn());
        assertThat(ativo.get("ativo").asBoolean()).isTrue();
        assertThat(auditoria("BANNER_PUBLICADO", id)).isEqualTo(1);

        JsonNode pub = null;
        for (JsonNode n : publicos()) if (n.get("id").asText().equals(id)) pub = n;
        assertThat(pub).isNotNull();
        assertThat(pub.has("ativo")).isFalse(); // campos de gestão nunca na montra pública
        assertThat(pub.has("ordem")).isFalse();
        assertThat(pub.has("atualizadoEm")).isFalse();
        assertThat(pub.get("imagem").asText()).isEqualTo(url);

        // O ficheiro da imagem é servido sem sessão (é referenciado por um banner), como as imagens de produto.
        mockMvc.perform(get(url)).andExpect(status().isOk());

        mockMvc.perform(put("/api/admin/banners/" + id).header("Authorization", bearer(t)).contentType("application/json")
                        .content("{\"ativo\":false}")).andExpect(status().isOk());
        assertThat(contem(publicos(), id)).isFalse();
        assertThat(auditoria("BANNER_DESPUBLICADO", id)).isEqualTo(1);
    }

    @Test
    void recusaImagensForaDoContrato() throws Exception {
        String t = admin();
        String id = criarValido(t, "Imagens").get("id").asText();

        imagem(t, id, new MockMultipartFile("image", "a.gif", "image/gif", "GIF89a".getBytes(StandardCharsets.US_ASCII)), 422);
        // diz ser PNG mas o conteúdo não é: o conteúdo é verificado, não só o tipo declarado.
        imagem(t, id, new MockMultipartFile("image", "falso.png", "image/png", "isto não é uma imagem".getBytes(StandardCharsets.UTF_8)), 422);
        imagem(t, id, new MockMultipartFile("image", "vazio.png", "image/png", new byte[0]), 422);
        // campo com outro nome
        imagem(t, id, new MockMultipartFile("ficheiro", "a.png", "image/png", PNG), 422);

        byte[] grande = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(PNG, 0, grande, 0, PNG.length);
        JsonNode e = imagem(t, id, new MockMultipartFile("image", "grande.png", "image/png", grande), 422).get("error");
        assertThat(e.get("message").asText()).contains("5 MB");

        assertThat(lista(t).toString()).doesNotContain("grande.png");
        mockMvc.perform(multipart("/api/admin/banners/nao-existe/imagem").file(png()).header("Authorization", bearer(t)))
                .andExpect(status().isNotFound());
    }

    // --- ordem ---------------------------------------------------------------------------------------------------

    @Test
    void reordenaTodosPelaOrdemRecebidaEOLiteralOrdemNaoEConfundidoComUmId() throws Exception {
        String t = admin();
        String a = criarValido(t, "A").get("id").asText();
        String b = criarValido(t, "B").get("id").asText();

        List<String> ids = new ArrayList<>();
        lista(t).forEach(n -> ids.add(n.get("id").asText()));
        Collections.reverse(ids);
        JsonNode r = json(mockMvc.perform(put("/api/admin/banners/ordem").header("Authorization", bearer(t))
                        .contentType("application/json").content(objectMapper.writeValueAsString(Map.of("ids", ids))))
                .andExpect(status().isOk()).andReturn());
        for (int i = 0; i < ids.size(); i++) {
            assertThat(r.get(i).get("id").asText()).isEqualTo(ids.get(i));
            assertThat(r.get(i).get("ordem").asInt()).isEqualTo(i + 1);
        }
        assertThat(r.get(0).get("id").asText()).isEqualTo(b);
        assertThat(lista(t).get(0).get("id").asText()).isEqualTo(b);
        assertThat(lista(t).get(1).get("id").asText()).isEqualTo(a);
        assertThat(auditoria("BANNER_REORDENADO", "ordem")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void recusaOrdemIncompletaRepetidaOuComIdDesconhecido() throws Exception {
        String t = admin();
        criarValido(t, "A");
        List<String> ids = new ArrayList<>();
        lista(t).forEach(n -> ids.add(n.get("id").asText()));

        List<String> faltaUm = new ArrayList<>(ids);
        faltaUm.remove(0);
        List<String> repetido = new ArrayList<>(ids);
        repetido.set(0, repetido.get(1));
        List<String> desconhecido = new ArrayList<>(ids);
        desconhecido.set(0, "nao-existe");
        for (Object corpo : List.of(Map.of("ids", faltaUm), Map.of("ids", repetido), Map.of("ids", desconhecido), Map.of("ids", "x"), Map.of())) {
            mockMvc.perform(put("/api/admin/banners/ordem").header("Authorization", bearer(t)).contentType("application/json")
                            .content(objectMapper.writeValueAsString(corpo)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.message").value("A nova ordem tem de incluir todos os banners, uma vez cada."));
        }
        // Nada mudou.
        List<String> depois = new ArrayList<>();
        lista(t).forEach(n -> depois.add(n.get("id").asText()));
        assertThat(depois).isEqualTo(ids);
    }

    // --- remover -------------------------------------------------------------------------------------------------

    @Test
    void removeEVoltaANumerarSemBuracos() throws Exception {
        String t = admin();
        String meio = criarValido(t, "Meio").get("id").asText();
        criarValido(t, "Fim");

        mockMvc.perform(delete("/api/admin/banners/" + meio).header("Authorization", bearer(t)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        assertThat(auditoria("BANNER_REMOVIDO", meio)).isEqualTo(1);

        JsonNode l = lista(t);
        assertThat(contem(l, meio)).isFalse();
        for (int i = 0; i < l.size(); i++) assertThat(l.get(i).get("ordem").asInt()).isEqualTo(i + 1);

        mockMvc.perform(delete("/api/admin/banners/" + meio).header("Authorization", bearer(t))).andExpect(status().isNotFound());
        mockMvc.perform(put("/api/admin/banners/" + meio).header("Authorization", bearer(t)).contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
    }

    // --- migração inicial ----------------------------------------------------------------------------------------

    @Test
    void migracaoV3TrouxeOsQuatroBannersDaHomepageActivosEComImagem() {
        entityManager.flush();
        List<String> ids = jdbcTemplate.queryForList("SELECT id FROM banners WHERE id IN ('bn-1','bn-2','bn-3','bn-4') AND ativo AND imagem LIKE '/banners/%'", String.class);
        assertThat(ids).containsExactlyInAnyOrder("bn-1", "bn-2", "bn-3", "bn-4");
    }
}
