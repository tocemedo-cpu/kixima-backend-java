package ao.kixima.security;

import ao.kixima.common.error.ErrorResponse;
import ao.kixima.user.User;
import ao.kixima.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Espelha backend/src/middleware/auth.js (`authenticate`) — verifica o JWT
 * (cookie httpOnly primeiro, Bearer depois), carrega o utilizador, confirma
 * `tokenVersion` (revogação server-side) e aplica a política de 2FA
 * obrigatória. Toda a decisão de acesso vive AQUI, num único filtro, tal
 * como no Node é um único middleware — não se usa o mecanismo de
 * autorização do Spring Security por baixo (ver SecurityConfig: permite
 * tudo à sua camada, para este filtro ser a única fonte de verdade,
 * exactamente como no Node).
 */
@Component
public class AuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final SessionCookieUtil sessionCookieUtil;
    private final UserRepository userRepository;
    private final MfaPolicyService mfaPolicyService;
    private final PublicPaths publicPaths;
    private final ObjectMapper objectMapper;

    public AuthenticationFilter(JwtService jwtService, SessionCookieUtil sessionCookieUtil,
                                 UserRepository userRepository, MfaPolicyService mfaPolicyService,
                                 PublicPaths publicPaths, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.sessionCookieUtil = sessionCookieUtil;
        this.userRepository = userRepository;
        this.mfaPolicyService = mfaPolicyService;
        this.publicPaths = publicPaths;
        this.objectMapper = objectMapper;
    }

    private String tokenDoPedido(HttpServletRequest req) {
        String doCookie = sessionCookieUtil.ler(req);
        if (doCookie != null) return doCookie;
        String header = req.getHeader("Authorization");
        if (header == null) return null;
        String[] parts = header.split(" ", 2);
        return parts.length == 2 && "Bearer".equals(parts[0]) && !parts[1].isBlank() ? parts[1] : null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        boolean publico = publicPaths.ePublico(request.getMethod(), path);
        String token = tokenDoPedido(request);

        if (token == null) {
            if (publico) {
                chain.doFilter(request, response);
            } else {
                responder401(response, "Sessão em falta. Inicie sessão para continuar.");
            }
            return;
        }

        Claims claims;
        try {
            claims = jwtService.verify(token);
        } catch (Exception e) {
            if (publico) {
                chain.doFilter(request, response); // rota pública com cookie inválido: ignora, tal como optionalAuthenticate.
            } else {
                responder401(response, "Token inválido ou expirado.");
            }
            return;
        }

        User user = userRepository.findByIdWithCompany(claims.getSubject()).orElse(null);
        if (user == null || !user.isActive()) {
            if (publico) {
                chain.doFilter(request, response);
            } else {
                responder401(response, "Utilizador inválido ou inativo.");
            }
            return;
        }

        Integer tvClaim = claims.get("tv", Integer.class);
        int tv = tvClaim == null ? 0 : tvClaim;
        if (tv != user.getTokenVersion()) {
            if (publico) {
                chain.doFilter(request, response);
            } else {
                responder401(response, "Sessão terminada. Inicie sessão novamente.");
            }
            return;
        }

        MfaPolicyService.Estado mfa = mfaPolicyService.estadoPara(user);
        CurrentUser currentUser = new CurrentUser(
                user.getId(), user.getRole(), user.getAdminAreas(), user.getCompanyId(),
                user.getCompany() == null ? null : user.getCompany().getType(),
                user.getCompany() == null ? null : user.getCompany().getPlan(),
                user.getApprovalCap(), user.getName(), user.getEmail(), user.getAvatarUrl(),
                mfa.pendente(), mfa.restrita(), mfa.prazo());

        if (mfa.restrita() && !mfaPolicyService.caminhoPermitido(path)) {
            responder403(response,
                    "A verificação em dois passos passou a ser obrigatória para o seu perfil. "
                            + "Ative-a em Segurança para voltar a usar a plataforma.");
            return;
        }

        CurrentUserHolder.set(currentUser);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUser, null, List.of()));
        try {
            chain.doFilter(request, response);
        } finally {
            CurrentUserHolder.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private void responder401(HttpServletResponse res, String mensagem) throws IOException {
        escrever(res, 401, "UNAUTHORIZED", mensagem);
    }

    private void responder403(HttpServletResponse res, String mensagem) throws IOException {
        escrever(res, 403, "FORBIDDEN", mensagem);
    }

    private void escrever(HttpServletResponse res, int status, String code, String mensagem) throws IOException {
        res.setStatus(status);
        res.setCharacterEncoding("UTF-8");
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write(objectMapper.writeValueAsString(ErrorResponse.of(code, mensagem)));
    }
}
