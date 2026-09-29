package ao.kixima.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Porte literal de tests/totp.test.js (partes puras, sem HTTP/DB) — mesmas
 * asserções, mesmos valores, para confirmar paridade byte-a-byte do RFC
 * 6238 com o utilitário Node (mesma janela ±1 passo, mesmo período de 30s).
 */
class TotpServiceTest {

    private final TotpService totp = new TotpService();

    @Test
    void geraCodigosEstaveisEVerificaComJanelaMaisMenosUm() {
        String secret = totp.generateSecret();
        long at = 1_700_000_000_000L;
        String code = totp.totp(secret, at);

        assertThat(code).matches("\\d{6}");
        assertThat(totp.verify(code, secret, at, 1)).isTrue();
        assertThat(totp.verify(code, secret, at + 30_000, 1)).isTrue();
        assertThat(totp.verify(code, secret, at + 90_000, 1)).isFalse();
        assertThat(totp.verify("000000", secret, at, 1)).isFalse();
    }

    @Test
    void desvioAte30sContinuaAceite() {
        String secret = totp.generateSecret();
        long agora = System.currentTimeMillis();
        for (int seg : new int[]{0, 25, -25}) {
            String code = totp.totp(secret, agora + seg * 1000L);
            assertThat(totp.verify(code, secret, agora, 1)).isTrue();
        }
    }

    @Test
    void acimaDissoERecusadoMasDizODesvioEOSentido() {
        String secret = totp.generateSecret();
        long agora = System.currentTimeMillis();

        String adiantado = totp.totp(secret, agora + 90 * 1000L);
        assertThat(totp.verify(adiantado, secret, agora, 1)).isFalse();
        assertThat(totp.explicarFalha(adiantado, secret, agora, 1)).contains("90 segundos adiantado");

        String atrasado = totp.totp(secret, agora - 300 * 1000L);
        assertThat(totp.explicarFalha(atrasado, secret, agora, 1)).contains("300 segundos atrasado");
    }

    @Test
    void umDesvioDetectadoNaoFazOCodigoPassar() {
        String secret = totp.generateSecret();
        long agora = System.currentTimeMillis();
        String fora = totp.totp(secret, agora + 120 * 1000L);
        assertThat(totp.desvioDeRelogio(fora, secret, agora, 1)).isEqualTo(120);
        assertThat(totp.verify(fora, secret, agora, 1)).isFalse();
    }

    @Test
    void codigoQueNaoBateApontaParaEntradasDuplicadas() {
        String secret = totp.generateSecret();
        long agora = System.currentTimeMillis();
        assertThat(totp.explicarFalha("123456", secret, agora, 1)).contains("mais do que uma vez");
    }

    @Test
    void foraDeMaisMenos10MinutosDeixaDeProcurarDesvio() {
        String secret = totp.generateSecret();
        long agora = System.currentTimeMillis();
        String longe = totp.totp(secret, agora + 900 * 1000L);
        assertThat(totp.desvioDeRelogio(longe, secret, agora, 1)).isNull();
    }
}
