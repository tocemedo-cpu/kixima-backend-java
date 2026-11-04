package ao.kixima.security;

import ao.kixima.common.error.UnauthorizedException;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sem Spring nem base de dados. {@code verify} é a verificação de SESSÃO (usada pelo filtro de autenticação e pelo
 * WebSocket): só aceita tokens de sessão. Os tokens auxiliares (claim {@code t}) têm o seu passo próprio e lêem-se com
 * {@code verifyRaw}. Ver auditoria A1.
 */
class JwtServiceTest {

    // Segredo aleatório por execução: nada fixo no repositório.
    private final JwtService jwt = new JwtService(UUID.randomUUID() + "-" + UUID.randomUUID(), "1d");

    @Test
    void tokenDeSessaoVerifica() {
        String token = jwt.signAccessToken("u1", PersonaRole.COMPRADOR, "c1", 3);
        Claims c = jwt.verify(token);
        assertThat(c.getSubject()).isEqualTo("u1");
        assertThat(c.get("tv", Integer.class)).isEqualTo(3);
    }

    @Test
    void tokensAuxiliaresNaoSaoSessao() {
        assertThatThrownBy(() -> jwt.verify(jwt.sign2faChallenge("u1", 0))).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> jwt.verify(jwt.signPasswordReset("u1", 0))).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> jwt.verify(jwt.signEmailVerification("u1"))).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> jwt.verify(jwt.signInvite("c1", "COMPRADOR", "i1"))).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void osTokensAuxiliaresContinuamALerSeNoSeuPasso() {
        // verifyRaw é o que cada passo usa (2FA, reset, email, convite): tem de continuar a funcionar.
        assertThat(jwt.verifyRaw(jwt.sign2faChallenge("u1", 2)).get("t")).isEqualTo("2fa");
        assertThat(jwt.verifyRaw(jwt.signPasswordReset("u1", 2)).get("t")).isEqualTo("pwreset");
        assertThat(jwt.verifyRaw(jwt.signEmailVerification("u1")).get("t")).isEqualTo("emailverify");
        assertThat(jwt.verifyRaw(jwt.signInvite("c1", "COMPRADOR", "i1")).get("t")).isEqualTo("invite");
    }

    @Test
    void lixoETokenAdulteradoSaoRecusados() {
        assertThatThrownBy(() -> jwt.verify("isto-nao-e-um-token")).isInstanceOf(UnauthorizedException.class);
        String token = jwt.signAccessToken("u1", PersonaRole.COMPRADOR, "c1", 0);
        assertThatThrownBy(() -> jwt.verify(token.substring(0, token.length() - 3) + "abc")).isInstanceOf(UnauthorizedException.class);
        JwtService outro = new JwtService(UUID.randomUUID() + "-" + UUID.randomUUID(), "1d");
        assertThatThrownBy(() -> outro.verify(token)).isInstanceOf(UnauthorizedException.class);
    }
}
