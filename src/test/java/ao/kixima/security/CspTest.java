package ao.kixima.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Espelha tests/csp.test.js — a CSP tem de deixar passar as imagens do bucket.
 *
 * Esta avaria não dá erro do lado do servidor — é o browser que recusa, e nos
 * registos não fica nada. Sem este teste só se descobre em produção, a olhar
 * para um catálogo sem fotografias.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CspTest {

    @Autowired
    private MockMvc mockMvc;

    private static ContentSecurityPolicy semArmazenamentoExterno() {
        return new ContentSecurityPolicy("", "", "", "", "", "");
    }

    @Test
    void semArmazenamentoExternoSoAPropriaOrigem() {
        assertThat(semArmazenamentoExterno().origensDeImagem()).isEmpty();
    }

    @Test
    void oUrlPublicoDoBucketEntraNaPolitica() {
        // Só a ORIGEM, não o caminho: o bucket inteiro é a unidade de confiança e
        // uma directiva com caminho é mais frágil sem ganhar nada.
        assertThat(ContentSecurityPolicy.origemDe("https://abc.supabase.co/storage/v1/object/public/product-images"))
                .isEqualTo("https://abc.supabase.co");
        ContentSecurityPolicy csp = new ContentSecurityPolicy(
                "https://abc.supabase.co/storage/v1/object/public/product-images", "", "", "", "", "");
        assertThat(csp.origensDeImagem()).containsExactly("https://abc.supabase.co");
    }

    @Test
    void asDirectivasMantemOQueOHelmetJaProtegia() {
        Map<String, List<String>> base = ContentSecurityPolicy.DIRECTIVAS_BASE_HELMET;
        Map<String, List<String>> d = semArmazenamentoExterno().directivas(base);
        // Não se afrouxa nada ao acrescentar o bucket.
        assertThat(d.get("script-src")).isEqualTo(base.get("script-src"));
        assertThat(d.get("object-src")).isEqualTo(base.get("object-src"));
        assertThat(d.get("img-src")).contains("'self'");
    }

    @Test
    void oCabecalhoSaiMesmoNasRespostas() throws Exception {
        String politica = mockMvc.perform(get("/health")).andExpect(status().isOk())
                .andReturn().getResponse().getHeader("Content-Security-Policy");
        assertThat(politica).isNotBlank();
        assertThat(politica).containsPattern("script-src 'self'");
        assertThat(politica).containsPattern("object-src 'none'");
        // blob: é preciso para as pré-visualizações de ficheiro antes do upload.
        assertThat(politica).containsPattern("img-src[^;]*blob:");
    }

    // --- Além do teste do Node: paridade byte a byte com o helmet 7.2.0 ------------

    @Test
    void oCabecalhoSemArmazenamentoExternoEIgualAoDoHelmetNoNode() {
        assertThat(semArmazenamentoExterno().cabecalho()).isEqualTo(
                "default-src 'self';base-uri 'self';font-src 'self' https: data:;form-action 'self';"
                        + "frame-ancestors 'self';img-src 'self' data: blob:;object-src 'none';script-src 'self';"
                        + "script-src-attr 'none';style-src 'self' https: 'unsafe-inline';upgrade-insecure-requests;"
                        + "connect-src 'self';frame-src 'self'");
    }

    @Test
    void oBucketEOSentryEntramNasDirectivasCertas() {
        ContentSecurityPolicy csp = new ContentSecurityPolicy(
                "https://cdn.exemplo.co.ao/x", "https://abc.supabase.co/storage/v1/s3", "fotos", "eu-west-1",
                "", "https://chave@o123.ingest.sentry.io/456");
        Map<String, List<String>> d = csp.directivas(ContentSecurityPolicy.DIRECTIVAS_BASE_HELMET);
        assertThat(d.get("img-src")).containsExactly("'self'", "data:", "blob:", "https://cdn.exemplo.co.ao", "https://abc.supabase.co");
        assertThat(d.get("frame-src")).containsExactly("'self'", "https://cdn.exemplo.co.ao", "https://abc.supabase.co");
        assertThat(d.get("connect-src")).containsExactly("'self'", "https://o123.ingest.sentry.io");

        // AWS sem endpoint próprio: o anfitrião é reconstruído a partir do bucket e da região.
        ContentSecurityPolicy aws = new ContentSecurityPolicy("", "", "fotos", "eu-west-1", "", "");
        assertThat(aws.origensDeImagem()).containsExactly("https://fotos.s3.eu-west-1.amazonaws.com");
        assertThat(ContentSecurityPolicy.origemDe("isto não é um url")).isNull();
        assertThat(ContentSecurityPolicy.origemDe("  https://A.B:8443/c ")).isEqualTo("https://a.b:8443");
    }

    @Test
    void osOutrosCabecalhosDoHelmetSaemComOsMesmosValores() throws Exception {
        mockMvc.perform(get("/health")).andExpect(status().isOk())
                .andExpect(header().string("Cross-Origin-Opener-Policy", "same-origin"))
                .andExpect(header().string("Cross-Origin-Resource-Policy", "cross-origin"))
                .andExpect(header().string("Origin-Agent-Cluster", "?1"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Strict-Transport-Security", "max-age=15552000; includeSubDomains"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-DNS-Prefetch-Control", "off"))
                .andExpect(header().string("X-Download-Options", "noopen"))
                .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"))
                .andExpect(header().string("X-Permitted-Cross-Domain-Policies", "none"))
                .andExpect(header().string("X-XSS-Protection", "0"))
                // O Node não emite os cabeçalhos de cache por omissão do Spring Security.
                .andExpect(header().doesNotExist("Cache-Control"))
                .andExpect(header().doesNotExist("Pragma"));
    }
}
