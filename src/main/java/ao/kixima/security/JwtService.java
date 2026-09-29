package ao.kixima.security;

import ao.kixima.common.error.UnauthorizedException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

/**
 * Espelha o uso de `jsonwebtoken` em backend/src/services/authService.js —
 * mesmo algoritmo (HS256), mesmo segredo (`JWT_SECRET`), e o mesmo padrão de
 * claims: `sub` (id do utilizador), mais um campo `t` para distinguir os
 * tokens de uso especial (convite, recuperação de senha, desafio 2FA) dos
 * tokens de sessão normais, tal como o Node.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final Duration accessTokenTtl;

    public JwtService(@Value("${kixima.auth.jwt-secret}") String secret,
                       @Value("${kixima.auth.jwt-expires-in:1d}") String expiresIn) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET em falta — tal como o Node, este processo recusa-se a arrancar sem ele.");
        }
        // jsonwebtoken usa o segredo como bytes UTF-8 crus (Buffer.from(secret)),
        // não Base64 — Keys.hmacShaKeyFor espera o mesmo.
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtl = JwtDuration.parse(expiresIn);
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    /** Token de sessão — espelha authService.signToken(user). `companyId` fica `null` para ADMIN_SISTEMA, tal como no Node. */
    public String signAccessToken(String userId, PersonaRole role, String companyId, int tokenVersion) {
        var claims = new java.util.HashMap<String, Object>();
        claims.put("sub", userId);
        claims.put("role", role.name());
        claims.put("companyId", companyId);
        claims.put("tv", tokenVersion);
        return sign(claims, accessTokenTtl);
    }

    /** Desafio de 2º passo do login — espelha o TWO_FA_CHALLENGE_TTL (15m) de authService.js. */
    public String sign2faChallenge(String userId, int tokenVersion) {
        return sign(Map.of("t", "2fa", "sub", userId, "tv", tokenVersion), Duration.ofMinutes(15));
    }

    /** Token de recuperação de senha — espelha o RESET_TTL (1h) de authService.js. */
    public String signPasswordReset(String userId, int tokenVersion) {
        return sign(Map.of("t", "pwreset", "sub", userId, "tv", tokenVersion), Duration.ofHours(1));
    }

    /** Token de convite — espelha o INVITE_TTL (7d) de authService.js. */
    public String signInvite(String companyId, String role, String inviteId) {
        var claims = new java.util.HashMap<String, Object>();
        claims.put("t", "invite");
        claims.put("companyId", companyId);
        claims.put("role", role);
        if (inviteId != null) claims.put("iid", inviteId);
        return sign(claims, Duration.ofDays(7));
    }

    private String sign(Map<String, Object> claims, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .claims(claims)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** Espelha jwt.verify(token, secret, {algorithms:['HS256']}) — lança UnauthorizedException com a mensagem genérica. */
    public Claims verify(String token) {
        try {
            return verifyRaw(token);
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthorizedException("Token inválido ou expirado.");
        }
    }

    /**
     * Como {@link #verify(String)}, mas deixa a excepção original subir —
     * usado onde o chamador precisa de uma mensagem própria por tipo de
     * token (desafio 2FA, recuperação de senha, convite), tal como cada
     * `try { jwt.verify(...) } catch { throw new UnauthorizedError('...') }`
     * próprio no Node (authService.js: utilizadorDoDesafio, verifyPasswordReset, verifyInvite).
     */
    public Claims verifyRaw(String token) throws JwtException {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
