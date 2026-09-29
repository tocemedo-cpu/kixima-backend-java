package ao.kixima.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Espelha backend/src/utils/sessionCookie.js — a sessão viaja num cookie
 * httpOnly (`kixima_sessao`), SameSite=Lax, Secure só em produção, com a
 * mesma validade do próprio JWT. O Bearer continua aceite à parte (ver
 * {@link AuthenticationFilter}) — não é backdoor, serve clientes
 * programáticos e os testes, tal como no Node.
 */
@Component
public class SessionCookieUtil {

    public static final String NOME = "kixima_sessao";

    private final JwtService jwtService;
    private final boolean secure;

    public SessionCookieUtil(JwtService jwtService,
                              @Value("${spring.profiles.active:}") String activeProfiles) {
        this.jwtService = jwtService;
        this.secure = activeProfiles.contains("prod");
    }

    public void definir(HttpServletResponse res, String token) {
        if (token == null) return;
        Cookie cookie = new Cookie(NOME, token);
        cookie.setHttpOnly(true);
        cookie.setSecure(secure);
        cookie.setPath("/");
        cookie.setMaxAge((int) jwtService.getAccessTokenTtl().toSeconds());
        cookie.setAttribute("SameSite", "Lax");
        res.addCookie(cookie);
    }

    public void limpar(HttpServletResponse res) {
        Cookie cookie = new Cookie(NOME, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(secure);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setAttribute("SameSite", "Lax");
        res.addCookie(cookie);
    }

    public String ler(HttpServletRequest req) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) {
            if (NOME.equals(c.getName())) return c.getValue();
        }
        return null;
    }
}
