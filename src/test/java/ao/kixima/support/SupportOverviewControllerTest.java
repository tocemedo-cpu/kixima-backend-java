package ao.kixima.support;

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

import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Espelha as secções "overview", "imagens das categorias" e "painel do Administrador" de tests/support.test.js (Lacunas D.6). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SupportOverviewControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";
    private static final String ADMIN_SISTEMA_EMAIL = "admin@kixima.co.ao";
    private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

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

    private JsonNode obter(String token, String url) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static JsonNode porKey(JsonNode lista, String key) {
        for (JsonNode n : lista) if (key.equals(n.get("key").asText())) return n;
        return null;
    }

    private static byte[] pngGrande(int extraBytes) {
        byte[] out = Arrays.copyOf(PNG, PNG.length + extraBytes);
        return out;
    }

    @Test
    void overviewImagensEPainelDoAdministrador() throws Exception {
        String token = login(COMPRADOR_EMAIL);
        String admin = login(ADMIN_SISTEMA_EMAIL);

        // Overview devolve categorias, canais, horário e contagem de tickets abertos; e diz se pode gerir imagens.
        JsonNode ov = obter(token, "/api/support/overview");
        assertThat(ov.get("categories").size()).isGreaterThan(0);
        assertThat(ov.get("channels").size()).isGreaterThan(0);
        assertThat(ov.get("hours").has("label")).isTrue();
        assertThat(ov.get("openTickets").isNumber()).isTrue();
        assertThat(ov.get("canManageImages").asBoolean()).isFalse();
        assertThat(obter(admin, "/api/support/overview").get("canManageImages").asBoolean()).isTrue();

        // Um não-admin não acede ao painel de administração (403).
        mockMvc.perform(get("/api/support/admin/overview").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());

        // Todos os locais têm imagem por omissão (bundled em /help).
        JsonNode adminOv = obter(admin, "/api/support/admin/overview");
        for (JsonNode s : adminOv.get("imageSlots")) assertThat(s.get("imageUrl").asText()).isNotBlank();
        assertThat(adminOv.get("imageSlots").size()).isGreaterThanOrEqualTo(15);
        Set<String> groups = new HashSet<>();
        for (JsonNode s : adminOv.get("imageSlots")) groups.add(s.get("group").asText());
        assertThat(groups).contains("Destaque", "Atalhos", "Categorias", "Canais");
        assertThat(adminOv.get("kpis").has("total")).isTrue();
        assertThat(ov.get("images").get("hero").asText()).isEqualTo("/help/hero.png");
        assertThat(porKey(ov.get("categories"), "catalogo").get("imageUrl").asText()).isEqualTo("/help/catalogo.jpg");
        for (JsonNode c : ov.get("channels")) assertThat(c.get("imageUrl").asText()).startsWith("/help/");

        // Override morto (upload efémero apagado num deploy) volta à imagem por omissão.
        jdbcTemplate.update("INSERT INTO support_category_images (key, image_url, updated_at) VALUES ('contratos', '/api/uploads/support-contratos-morto.jpg', now())");
        entityManager.clear();
        assertThat(porKey(obter(token, "/api/support/overview").get("categories"), "contratos").get("imageUrl").asText()).isEqualTo("/help/contratos.jpg");

        // O Admin do Sistema faz upload da imagem de uma categoria; um não-admin não pode (403).
        mockMvc.perform(multipart("/api/support/categories/ordens/image").file(new MockMultipartFile("image", "ordens.png", "image/png", PNG))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").isString());
        assertThat(porKey(obter(token, "/api/support/overview").get("categories"), "ordens").get("imageUrl").asText()).startsWith("/api/uploads/");
        mockMvc.perform(multipart("/api/support/categories/ordens/image").file(new MockMultipartFile("image", "x.png", "image/png", PNG))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        // Admin gere todos os locais (hero, atalhos, canais…); slot inválido → 404; sem ficheiro → 400.
        mockMvc.perform(multipart("/api/support/images/hero").file(new MockMultipartFile("image", "hero.png", "image/png", PNG)).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        assertThat(obter(token, "/api/support/overview").get("images").get("hero").asText()).startsWith("/api/uploads/");
        mockMvc.perform(multipart("/api/support/images/inexistente").file(new MockMultipartFile("image", "x.png", "image/png", PNG)).header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());
        mockMvc.perform(multipart("/api/support/images/hero").header("Authorization", "Bearer " + admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("NO_FILE"));
        // Remover o override volta ao default.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/support/images/hero").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.key").value("hero"));
        assertThat(obter(token, "/api/support/overview").get("images").get("hero").asText()).isEqualTo("/help/hero.png");

        // Formato não suportado (HEIC) → 422 claro; 8 MB dentro do limite é aceite; acima do limite → 413 claro.
        byte[] heic = new byte[]{0, 0, 0, 0x1c, 0x66, 0x74, 0x79, 0x70, 0x68, 0x65, 0x69, 0x63, 0, 0};
        mockMvc.perform(multipart("/api/support/images/hero").file(new MockMultipartFile("image", "foto.heic", "image/heic", heic)).header("Authorization", "Bearer " + admin))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("HEIC")));
        mockMvc.perform(multipart("/api/support/images/hero").file(new MockMultipartFile("image", "grande.png", "image/png", pngGrande(8 * 1024 * 1024))).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").isString());
        mockMvc.perform(multipart("/api/support/images/hero").file(new MockMultipartFile("image", "enorme.png", "image/png", pngGrande(13 * 1024 * 1024))).header("Authorization", "Bearer " + admin))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsStringIgnoringCase("demasiado grande")));
    }
}
