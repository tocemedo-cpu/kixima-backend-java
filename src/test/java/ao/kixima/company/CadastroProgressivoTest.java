package ao.kixima.company;

import ao.kixima.security.JwtService;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cadastro empresarial em etapas: registo da conta (tipo + nome + email + senha), confirmação do email, entrada com a
 * empresa ainda por aprovar, guardar/recuperar o cadastro, submissão, decisão da KIXIMA e — em todos os passos — o
 * portão que impede o que depende de aprovação, mesmo chamando os endpoints directamente.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CadastroProgressivoTest {

    private static final String SENHA = "SenhaForte@2026xyz";
    private static final byte[] PDF = "%PDF-1.4 documento de teste".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtService jwtService;
    @PersistenceContext private EntityManager entityManager;

    // --- ajudantes ------------------------------------------------------------------------------------------------

    private static String sufixo() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private ResultActions json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, String token, Object corpo) throws Exception {
        if (token != null) b.header("Authorization", "Bearer " + token);
        return mockMvc.perform(b.contentType("application/json").content(objectMapper.writeValueAsString(corpo)));
    }

    private ResultActions registar(String tipo, String email) throws Exception {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("type", tipo);
        corpo.put("name", "Ana Administradora");
        corpo.put("email", email);
        corpo.put("password", SENHA);
        corpo.put("termsAccepted", true);
        ResultActions r = json(post("/api/auth/register"), null, corpo);
        // Cada pedido real tem o seu contexto de persistência; aqui partilha-se, e a relação User.company (só de leitura)
        // não se carregaria sozinha para o utilizador acabado de criar.
        entityManager.flush();
        entityManager.clear();
        return r;
    }

    private ResultActions entrar(String email, String senha) throws Exception {
        return json(post("/api/auth/login"), null, Map.of("email", email, "password", senha));
    }

    private String token(String email, String senha) throws Exception {
        var res = entrar(email, senha).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    private void confirmarEmail(String email) throws Exception {
        entityManager.flush();
        String userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", String.class, email);
        json(post("/api/auth/verify-email"), null, Map.of("token", jwtService.signEmailVerification(userId))).andExpect(status().isOk());
    }

    /** Conta nova já confirmada e com sessão; devolve o token. */
    private String contaConfirmada(String tipo, String email) throws Exception {
        registar(tipo, email).andExpect(status().isCreated());
        confirmarEmail(email);
        return token(email, SENHA);
    }

    private String adminToken() throws Exception {
        return token("admin@kixima.co.ao", "Kixima@123");
    }

    private ResultActions doc(String token, String tipo, String ficheiro, byte[] conteudo, String mime) throws Exception {
        return mockMvc.perform(multipart("/api/onboarding/documents/" + tipo)
                .file(new MockMultipartFile("file", ficheiro, mime, conteudo)).header("Authorization", "Bearer " + token));
    }

    private ResultActions guardar(String token, Map<String, ?> campos) throws Exception {
        return json(put("/api/onboarding"), token, campos);
    }

    private void preencherOperadora(String token, String nif) throws Exception {
        guardar(token, Map.of("name", "Operadora " + nif, "taxId", nif, "contactPhone", "+244 923 000 000", "employees", "300")).andExpect(status().isOk());
        doc(token, "CERTIDAO_COMERCIAL", "certidao.pdf", PDF, "application/pdf").andExpect(status().isOk());
        doc(token, "ALVARA_COMERCIAL", "alvara.pdf", PDF, "application/pdf").andExpect(status().isOk());
    }

    private String companyIdDe(String email) {
        entityManager.flush();
        return jdbcTemplate.queryForObject("SELECT company_id FROM users WHERE email = ?", String.class, email);
    }

    private String estadoDe(String companyId) {
        entityManager.flush();
        return jdbcTemplate.queryForObject("SELECT status::text FROM companies WHERE id = ?", String.class, companyId);
    }

    // --- registo e confirmação do email ---------------------------------------------------------------------------

    @Test
    void registoCriaContaERascunhoSemNomeNemNifInventados() throws Exception {
        String email = "ana@nova-" + sufixo() + ".co.ao";
        registar("FORNECEDOR", email).andExpect(status().isCreated()).andExpect(jsonPath("$.emailVerificationRequired").value(true));
        entityManager.flush();

        Map<String, Object> empresa = jdbcTemplate.queryForMap(
                "SELECT c.status::text AS status, c.type::text AS type, c.name, c.tax_id, c.contact_email FROM companies c JOIN users u ON u.company_id = c.id WHERE u.email = ?", email);
        assertThat(empresa.get("status")).isEqualTo("RASCUNHO");
        assertThat(empresa.get("type")).isEqualTo("FORNECEDOR");
        assertThat(empresa.get("name")).isNull();
        assertThat(empresa.get("tax_id")).isNull();
        assertThat(empresa.get("contact_email")).isEqualTo(email);
        Map<String, Object> conta = jdbcTemplate.queryForMap("SELECT role::text AS role, email_verified_at FROM users WHERE email = ?", email);
        assertThat(conta.get("role")).isEqualTo("COMPANY_ADMIN");
        assertThat(conta.get("email_verified_at")).isNull();
    }

    @Test
    void registoValidaTipoTermosSenhaEEmailRepetido() throws Exception {
        String email = "ana@valida-" + sufixo() + ".co.ao";
        registar("OUTRO", email).andExpect(status().isUnprocessableEntity());
        json(post("/api/auth/register"), null, Map.of("type", "CLIENTE", "name", "Ana", "email", email, "password", SENHA, "termsAccepted", false))
                .andExpect(status().isUnprocessableEntity());
        json(post("/api/auth/register"), null, Map.of("type", "CLIENTE", "name", "Ana", "email", email, "password", "curta", "termsAccepted", true))
                .andExpect(status().isUnprocessableEntity());
        registar("CLIENTE", email).andExpect(status().isCreated());
        registar("CLIENTE", email).andExpect(status().isConflict());
    }

    @Test
    void semConfirmarOEmailNaoHaAcessoEDepoisDeConfirmarHa() throws Exception {
        String email = "ana@confirma-" + sufixo() + ".co.ao";
        registar("CLIENTE", email).andExpect(status().isCreated());

        entrar(email, SENHA).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("EMAIL_NOT_VERIFIED"));
        // Com a senha errada continua a ser «credenciais inválidas» — não se revela o estado da conta.
        entrar(email, "SenhaErrada@2026xyz").andExpect(status().isUnauthorized());

        confirmarEmail(email);
        entrar(email, SENHA).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("COMPANY_ADMIN"))
                .andExpect(jsonPath("$.user.companyStatus").value("RASCUNHO"))
                .andExpect(jsonPath("$.user.companyType").value("CLIENTE"));
        // Confirmar duas vezes é inofensivo.
        confirmarEmail(email);
    }

    @Test
    void linkDeConfirmacaoInvalidoEReenvioSemRevelarContas() throws Exception {
        json(post("/api/auth/verify-email"), null, Map.of("token", "isto-nao-e-um-token")).andExpect(status().isBadRequest());
        // Um token de outro tipo (sessão) não serve para confirmar emails.
        String sessao = token("comprador@petroangola.co.ao", "Kixima@123");
        json(post("/api/auth/verify-email"), null, Map.of("token", sessao)).andExpect(status().isBadRequest());

        String resposta = json(post("/api/auth/resend-verification"), null, Map.of("email", "ninguem-" + sufixo() + "@x.co.ao"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String existente = json(post("/api/auth/resend-verification"), null, Map.of("email", "comprador@petroangola.co.ao"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(existente).isEqualTo(resposta);
    }

    @Test
    void contasQueJaExistiamEntramSemConfirmarEmail() throws Exception {
        entrar("comprador@petroangola.co.ao", "Kixima@123").andExpect(status().isOk()).andExpect(jsonPath("$.user.companyStatus").value("APROVADA"));
    }

    // --- portão de aprovação ----------------------------------------------------------------------------------------

    @Test
    void empresaPorAprovarSoChegaASessaoEAoCadastro() throws Exception {
        String email = "ana@portao-" + sufixo() + ".co.ao";
        String token = contaConfirmada("CLIENTE", email);
        String companyId = companyIdDe(email);

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.companyStatus").value("RASCUNHO"));
        mockMvc.perform(get("/api/onboarding").header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        // Directamente nos endpoints de negócio: recusado, com um código que o frontend reconhece.
        for (String caminho : new String[]{"/api/notifications", "/api/companies/" + companyId + "/subscription", "/api/companies/" + companyId + "/bank-details"}) {
            mockMvc.perform(get(caminho).header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMPANY_NOT_APPROVED"));
        }
        json(post("/api/companies/users"), token, Map.of("name", "Outro", "email", "outro@x.co.ao", "password", SENHA, "role", "COMPRADOR"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("COMPANY_NOT_APPROVED"));
    }

    @Test
    void oPortaoMantemseEmAnaliseERejeitadaELevantaseComAAprovacao() throws Exception {
        String email = "ana@estados-" + sufixo() + ".co.ao";
        String token = contaConfirmada("CLIENTE", email);
        String companyId = companyIdDe(email);
        preencherOperadora(token, "AO-EST-" + sufixo());
        mockMvc.perform(post("/api/onboarding/submit").header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        // PENDENTE: continua sem acesso ao negócio, mas o token que já tinha passa a dizê-lo logo (sem novo login).
        mockMvc.perform(get("/api/notifications").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.message").value(containsString("em análise")));

        String admin = adminToken();
        json(patch("/api/companies/" + companyId + "/decision"), admin, Map.of("approve", false, "rejectionReason", "Alvará ilegível"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJEITADA")).andExpect(jsonPath("$.rejectionReason").value("Alvará ilegível"));
        mockMvc.perform(get("/api/notifications").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());

        json(patch("/api/companies/" + companyId + "/decision"), admin, Map.of("approve", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APROVADA")).andExpect(jsonPath("$.rejectionReason").doesNotExist());
        // Aprovada: o mesmo token já entra nas funcionalidades da empresa.
        mockMvc.perform(get("/api/notifications").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mockMvc.perform(get("/api/companies/" + companyId + "/subscription").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mockMvc.perform(get("/api/onboarding").header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andExpect(jsonPath("$.editable").value(false));
    }

    @Test
    void soOAdministradorDaEmpresaUsaOCadastro() throws Exception {
        String comprador = token("comprador@petroangola.co.ao", "Kixima@123");
        mockMvc.perform(get("/api/onboarding").header("Authorization", "Bearer " + comprador)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/onboarding")).andExpect(status().isUnauthorized());
    }

    // --- cadastro em etapas ------------------------------------------------------------------------------------------

    @Test
    void guardaERecuperaOCadastroSemPedirDeNovoOQueJaFoiPreenchido() throws Exception {
        String email = "ana@etapas-" + sufixo() + ".co.ao";
        String token = contaConfirmada("CLIENTE", email);

        // Estado inicial: o email da conta serve de contacto; nada mais é inventado.
        mockMvc.perform(get("/api/onboarding").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RASCUNHO"))
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.empresa.name").doesNotExist())
                .andExpect(jsonPath("$.empresa.contactEmail").value(email))
                .andExpect(jsonPath("$.podeSubmeter").value(false))
                .andExpect(jsonPath("$.documentos.length()").value(2))
                .andExpect(jsonPath("$.apolice").doesNotExist());

        // Etapa 1 guardada; sai e volta mais tarde (novo login = nova sessão).
        guardar(token, Map.of("name", "Petrolífera do Sul", "taxId", "AO-" + sufixo())).andExpect(status().isOk());
        doc(token, "ALVARA_COMERCIAL", "alvara.pdf", PDF, "application/pdf").andExpect(status().isOk())
                .andExpect(jsonPath("$.documentos[?(@.type == 'ALVARA_COMERCIAL')].uploaded").value(true))
                .andExpect(jsonPath("$.documentos[?(@.type == 'ALVARA_COMERCIAL')].originalName").value("alvara.pdf"));

        String novoToken = token(email, SENHA);
        mockMvc.perform(get("/api/onboarding").header("Authorization", "Bearer " + novoToken))
                .andExpect(jsonPath("$.empresa.name").value("Petrolífera do Sul"))
                .andExpect(jsonPath("$.documentos[?(@.type == 'ALVARA_COMERCIAL')].uploaded").value(true))
                .andExpect(jsonPath("$.documentos[?(@.type == 'CERTIDAO_COMERCIAL')].uploaded").value(false))
                .andExpect(jsonPath("$.etapas[?(@.key == 'empresa')].complete").value(true))
                .andExpect(jsonPath("$.etapas[?(@.key == 'documentos')].complete").value(false))
                .andExpect(jsonPath("$.pendencias[0]").value("Certidão Comercial"));

        // Guardar só um campo não apaga os outros; substituir um documento mantém um só por tipo.
        guardar(novoToken, Map.of("address", "Rua da Missão 12, Luanda")).andExpect(status().isOk())
                .andExpect(jsonPath("$.empresa.name").value("Petrolífera do Sul"))
                .andExpect(jsonPath("$.empresa.address").value("Rua da Missão 12, Luanda"));
        doc(novoToken, "ALVARA_COMERCIAL", "alvara-novo.pdf", PDF, "application/pdf").andExpect(status().isOk())
                .andExpect(jsonPath("$.documentos[?(@.type == 'ALVARA_COMERCIAL')].originalName").value("alvara-novo.pdf"));
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM company_documents WHERE company_id = ? AND type = 'ALVARA_COMERCIAL'",
                Integer.class, companyIdDe(email))).isEqualTo(1);
    }

    @Test
    void validaOsCamposAoGuardarEORespeitaOTipoDeEmpresa() throws Exception {
        String email = "ana@valida2-" + sufixo() + ".co.ao";
        String token = contaConfirmada("CLIENTE", email);

        guardar(token, Map.of("contactEmail", "isto-nao-e-email")).andExpect(status().isUnprocessableEntity());
        guardar(token, Map.of("employees", "-3")).andExpect(status().isUnprocessableEntity());
        guardar(token, Map.of("name", "X")).andExpect(status().isUnprocessableEntity());
        // NIF de outra empresa -> 409.
        String nifExistente = jdbcTemplate.queryForObject("SELECT tax_id FROM companies WHERE tax_id IS NOT NULL LIMIT 1", String.class);
        guardar(token, Map.of("taxId", nifExistente)).andExpect(status().isConflict());
        // Uma Operadora não tem apólice nem licença ANPG.
        guardar(token, Map.of("insurer", "Seguradora X")).andExpect(status().isUnprocessableEntity());
        doc(token, "LICENCA_ANPG", "anpg.pdf", PDF, "application/pdf").andExpect(status().isUnprocessableEntity());
        doc(token, "APOLICE_SEGURO", "apolice.pdf", PDF, "application/pdf").andExpect(status().isUnprocessableEntity());
        doc(token, "QUALQUER_COISA", "x.pdf", PDF, "application/pdf").andExpect(status().isUnprocessableEntity());
        // Formato do ficheiro e logótipo.
        doc(token, "ALVARA_COMERCIAL", "x.txt", "texto".getBytes(StandardCharsets.UTF_8), "text/plain").andExpect(status().isUnprocessableEntity());
        doc(token, "LOGOTIPO", "logo.pdf", PDF, "application/pdf").andExpect(status().isUnprocessableEntity());
        doc(token, "LOGOTIPO", "logo.png", PNG, "image/png").andExpect(status().isOk()).andExpect(jsonPath("$.empresa.logoUrl").isString());
    }

    @Test
    void submissaoIncompletaListaOQueFaltaECompletaPassaAPendente() throws Exception {
        String email = "ana@submete-" + sufixo() + ".co.ao";
        String token = contaConfirmada("CLIENTE", email);
        String companyId = companyIdDe(email);

        mockMvc.perform(post("/api/onboarding/submit").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(containsString("Nome da empresa")))
                .andExpect(jsonPath("$.error.message").value(containsString("NIF")))
                .andExpect(jsonPath("$.error.message").value(containsString("Certidão Comercial")));
        assertThat(estadoDe(companyId)).isEqualTo("RASCUNHO");

        preencherOperadora(token, "AO-SUB-" + sufixo());
        mockMvc.perform(get("/api/onboarding").header("Authorization", "Bearer " + token)).andExpect(jsonPath("$.podeSubmeter").value(true));
        mockMvc.perform(post("/api/onboarding/submit").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDENTE"))
                .andExpect(jsonPath("$.editable").value(false))
                .andExpect(jsonPath("$.submittedAt").isString());
        assertThat(estadoDe(companyId)).isEqualTo("PENDENTE");
        // 300 trabalhadores = GRANDE -> PRO, como no cadastro antigo.
        assertThat(jdbcTemplate.queryForObject("SELECT plan::text FROM companies WHERE id = ?", String.class, companyId)).isEqualTo("PRO");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'CADASTRO_SUBMETIDO' AND entity_id = ?", Integer.class, companyId)).isEqualTo(1);

        // Em análise: nada se altera e não se submete duas vezes.
        guardar(token, Map.of("name", "Outro Nome")).andExpect(status().isBadRequest());
        doc(token, "ALVARA_COMERCIAL", "outro.pdf", PDF, "application/pdf").andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/onboarding/submit").header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());

        // A KIXIMA vê-o na lista de due diligence, com documentos.
        String admin = adminToken();
        mockMvc.perform(get("/api/companies").param("status", "PENDENTE").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$[?(@.id == '" + companyId + "')].submittedAt").isNotEmpty());
        mockMvc.perform(get("/api/companies/" + companyId).header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.documents.length()").value(2));
    }

    @Test
    void prestadoraExigeALicencaAnpgEAApolice() throws Exception {
        String email = "ana@prestadora-" + sufixo() + ".co.ao";
        String token = contaConfirmada("FORNECEDOR", email);
        String companyId = companyIdDe(email);

        mockMvc.perform(get("/api/onboarding").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.documentos.length()").value(3))
                .andExpect(jsonPath("$.documentos[?(@.type == 'LICENCA_ANPG')].required").value(true))
                .andExpect(jsonPath("$.apolice").exists())
                .andExpect(jsonPath("$.etapas[?(@.key == 'apolice')]").exists());

        guardar(token, Map.of("name", "Serviços Offshore", "taxId", "AO-PRE-" + sufixo())).andExpect(status().isOk());
        for (String tipo : new String[]{"CERTIDAO_COMERCIAL", "ALVARA_COMERCIAL"}) doc(token, tipo, tipo + ".pdf", PDF, "application/pdf").andExpect(status().isOk());
        mockMvc.perform(post("/api/onboarding/submit").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(containsString("Licença da ANPG")))
                .andExpect(jsonPath("$.error.message").value(containsString("Seguradora")))
                .andExpect(jsonPath("$.error.message").value(containsString("Documento da apólice")));

        doc(token, "LICENCA_ANPG", "anpg.pdf", PDF, "application/pdf").andExpect(status().isOk());
        Map<String, Object> apolice = new LinkedHashMap<>();
        apolice.put("insurer", "Ensa");
        apolice.put("policyNumber", "AP-123");
        apolice.put("coverageAmount", "5000000");
        apolice.put("policyCurrency", "USD");
        apolice.put("policyValidFrom", "2026-01-01");
        apolice.put("policyValidUntil", "2027-01-01");
        guardar(token, apolice).andExpect(status().isOk());
        doc(token, "APOLICE_SEGURO", "apolice.pdf", PDF, "application/pdf").andExpect(status().isOk());

        // Volta mais tarde: a apólice guardada em rascunho vem preenchida.
        mockMvc.perform(get("/api/onboarding").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.apolice.insurer").value("Ensa"))
                .andExpect(jsonPath("$.apolice.coverageAmount").value("5000000"))
                .andExpect(jsonPath("$.apolice.currency").value("USD"))
                .andExpect(jsonPath("$.apolice.documentName").value("apolice.pdf"))
                .andExpect(jsonPath("$.podeSubmeter").value(true));
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM supplier_to_kixima_policies WHERE company_id = ?", Integer.class, companyId)).isZero();

        mockMvc.perform(post("/api/onboarding/submit").header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDENTE"));
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM supplier_to_kixima_policies WHERE company_id = ? AND status::text = 'SUBMETIDA'", Integer.class, companyId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT onboarding_draft FROM companies WHERE id = ?", String.class, companyId)).isNull();

        // Com a apólice submetida, a KIXIMA pode aprovar (regra existente).
        json(patch("/api/companies/" + companyId + "/decision"), adminToken(), Map.of("approve", true)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APROVADA"));
        entrar(email, SENHA).andExpect(status().isOk()).andExpect(jsonPath("$.user.companyStatus").value("APROVADA"));
    }

    @Test
    void empresaRejeitadaCorrigeEVoltaASubmeter() throws Exception {
        String email = "ana@rejeitada-" + sufixo() + ".co.ao";
        String token = contaConfirmada("CLIENTE", email);
        String companyId = companyIdDe(email);
        preencherOperadora(token, "AO-REJ-" + sufixo());
        mockMvc.perform(post("/api/onboarding/submit").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        json(patch("/api/companies/" + companyId + "/decision"), adminToken(), Map.of("approve", false, "rejectionReason", "NIF não confere com a certidão"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/onboarding").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.status").value("REJEITADA"))
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.rejectionReason").value("NIF não confere com a certidão"))
                .andExpect(jsonPath("$.podeSubmeter").value(true));

        guardar(token, Map.of("taxId", "AO-CORR-" + sufixo())).andExpect(status().isOk());
        mockMvc.perform(post("/api/onboarding/submit").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDENTE"))
                .andExpect(jsonPath("$.rejectionReason").doesNotExist());
    }

    @Test
    void aKiximaNaoDecideRascunhosENaoOsListaPorOmissao() throws Exception {
        String email = "ana@rascunho-" + sufixo() + ".co.ao";
        contaConfirmada("CLIENTE", email);
        String companyId = companyIdDe(email);
        String admin = adminToken();

        json(patch("/api/companies/" + companyId + "/decision"), admin, Map.of("approve", true)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/companies").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == '" + companyId + "')]").isEmpty());
        mockMvc.perform(get("/api/companies").param("status", "RASCUNHO").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == '" + companyId + "')]").isNotEmpty());
        JsonNode ficha = objectMapper.readTree(mockMvc.perform(get("/api/companies/" + companyId).header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString());
        assertThat(ficha.get("status").asText()).isEqualTo("RASCUNHO");
    }

    @Test
    void ficheirosDoCadastroSoAbremAEmpresaQueOsEnviouEAKixima() throws Exception {
        String email = "ana@ficheiros-" + sufixo() + ".co.ao";
        String token = contaConfirmada("FORNECEDOR", email);
        var resposta = doc(token, "LOGOTIPO", "logo.png", PNG, "image/png").andExpect(status().isOk()).andReturn();
        String logo = objectMapper.readTree(resposta.getResponse().getContentAsString()).at("/empresa/logoUrl").asText();
        var docResposta = doc(token, "ALVARA_COMERCIAL", "alvara.pdf", PDF, "application/pdf").andExpect(status().isOk()).andReturn();
        String alvara = null;
        for (JsonNode d : objectMapper.readTree(docResposta.getResponse().getContentAsString()).at("/documentos")) {
            if ("ALVARA_COMERCIAL".equals(d.get("type").asText())) alvara = d.get("fileUrl").asText();
        }
        var apoResposta = doc(token, "APOLICE_SEGURO", "apolice.pdf", PDF, "application/pdf").andExpect(status().isOk()).andReturn();
        String apolice = objectMapper.readTree(apoResposta.getResponse().getContentAsString()).at("/apolice/documentUrl").asText();
        entityManager.flush();
        entityManager.clear();

        // Logótipo: público (a montra pública mostra-o).
        mockMvc.perform(get(logo)).andExpect(status().isOk());
        // Documentos e apólice: a própria empresa e a KIXIMA; anónimos 401; outra empresa 404.
        for (String url : new String[]{alvara, apolice}) {
            mockMvc.perform(get(url)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(url).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
            mockMvc.perform(get(url).header("Authorization", "Bearer " + adminToken())).andExpect(status().isOk());
            mockMvc.perform(get(url).header("Authorization", "Bearer " + token("comprador@petroangola.co.ao", "Kixima@123"))).andExpect(status().isNotFound());
        }
    }
}
