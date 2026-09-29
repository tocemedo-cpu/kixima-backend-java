package ao.kixima.auth;

import ao.kixima.common.error.BusinessRuleException;
import ao.kixima.common.error.ConflictException;
import ao.kixima.common.error.ForbiddenException;
import ao.kixima.common.error.UnauthorizedException;
import ao.kixima.common.error.ValidationException;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyStatus;
import ao.kixima.notification.EmailDispatchService;
import ao.kixima.security.JwtService;
import ao.kixima.security.MfaPolicyService;
import ao.kixima.security.TotpService;
import ao.kixima.user.LoginAttemptService;
import ao.kixima.user.PasswordPolicy;
import ao.kixima.user.User;
import ao.kixima.user.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Espelha backend/src/services/authService.js — login (com o 2º passo por
 * app de autenticação ou por código de EMAIL), recuperação de senha por
 * email, e a gestão da verificação em dois passos.
 *
 * Transações: no Node cada {@code prisma.user.update} é escrito de imediato,
 * mesmo que a função lance a seguir (tentativa de 2FA errada contada,
 * código apagado depois de um envio falhado, falha de senha registada). Aqui
 * o utilizador é uma entidade gerida numa transação Spring, que por omissão
 * REVERTIA essas escritas ao lançar — por isso os métodos que escrevem e a
 * seguir recusam declaram {@code noRollbackFor} para as excepções de negócio
 * que fazem parte do fluxo normal (401/400), mantendo o estado que o Node
 * também mantém.
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginAttemptService loginAttemptService;
    private final MfaPolicyService mfaPolicyService;
    private final PasswordPolicy passwordPolicy;
    private final TotpService totpService;
    private final MfaEmailService mfaEmailService;
    private final EmailDispatchService emailDispatchService;
    private final String appUrl;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService,
                        LoginAttemptService loginAttemptService, MfaPolicyService mfaPolicyService,
                        PasswordPolicy passwordPolicy, TotpService totpService, MfaEmailService mfaEmailService,
                        EmailDispatchService emailDispatchService, @Value("${kixima.app-url:}") String appUrl) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginAttemptService = loginAttemptService;
        this.mfaPolicyService = mfaPolicyService;
        this.passwordPolicy = passwordPolicy;
        this.totpService = totpService;
        this.mfaEmailService = mfaEmailService;
        this.emailDispatchService = emailDispatchService;
        this.appUrl = appUrl == null ? "" : appUrl;
    }

    /**
     * {@code noRollbackFor}: a senha errada é registada (bloqueio progressivo)
     * ANTES do 401 — e tem de ficar registada, senão o bloqueio nunca chega a
     * acontecer. O mesmo para o 400 do envio do código por email, que já
     * apagou o código pendente e limpou o rasto das falhas.
     */
    @Transactional(noRollbackFor = {UnauthorizedException.class, BusinessRuleException.class})
    public LoginResponse login(String email, String password) {
        User user = userRepository.findByEmailWithCompany(email).orElse(null);
        if (user == null) {
            throw new UnauthorizedException("Credenciais inválidas.");
        }

        // Verificado ANTES da comparação de senha — o bcrypt é caro de propósito.
        loginAttemptService.assertNaoBloqueado(user);

        if (!user.isActive()) {
            throw new ForbiddenException("A sua conta ainda aguarda aprovação do administrador da empresa.");
        }
        Company company = user.getCompany();
        if (company != null && company.getStatus() != CompanyStatus.APROVADA) {
            throw new ForbiddenException("A empresa ainda não foi aprovada no cadastro (due diligence).");
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            loginAttemptService.registarFalha(user);
            throw new UnauthorizedException("Credenciais inválidas.");
        }
        // Senha certa: apaga o rasto das falhas anteriores. Antes da 2FA de propósito.
        loginAttemptService.limpar(user);

        // 2FA ativa: a senha não basta. Devolve um desafio de curta duração; o token
        // de sessão só sai no /2fa/verify com um código válido.
        if (user.getTotpEnabledAt() != null) {
            String challenge = jwtService.sign2faChallenge(user.getId(), user.getTokenVersion());
            String metodo = user.getMfaMethod() != null ? user.getMfaMethod() : "TOTP";
            if (!"EMAIL".equals(metodo)) {
                return LoginResponse.desafio(metodo, challenge);
            }
            // Método EMAIL: o código é enviado agora. Se o envio falhar, dizemos —
            // engolir o erro deixaria a pessoa à espera de um código que não existe,
            // sem forma nenhuma de entrar.
            MfaEmailService.Envio envio = mfaEmailService.enviarCodigo(user, "login", true);
            return LoginResponse.desafioComEnvio(metodo, challenge, envio);
        }

        return buildSession(user);
    }

    private LoginResponse buildSession(User user) {
        MfaPolicyService.Estado mfa = mfaPolicyService.estadoPara(user);
        String token = jwtService.signAccessToken(user.getId(), user.getRole(), user.getCompanyId(), user.getTokenVersion());
        Company company = user.getCompany();
        UserSessionDto dto = new UserSessionDto(
                user.getId(), user.getName(), user.getEmail(), user.getRole(), user.getAdminAreas(),
                user.getCompanyId(), company == null ? null : company.getName(),
                company == null ? null : company.getType(), user.getAvatarUrl());
        return LoginResponse.sessao(token, mfa.pendente(), mfa.restrita(), mfa.prazo(), dto);
    }

    @Transactional
    public void changePassword(String userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId).orElseThrow(() -> new UnauthorizedException("Sessão inválida."));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new UnauthorizedException("A senha atual está incorreta.");
        }
        String erro = passwordPolicy.validar(newPassword, user.getRole(), user.getEmail());
        if (erro != null) throw new ValidationException(erro);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setTokenVersion(user.getTokenVersion() + 1);
    }

    /** Logout global — revoga todas as sessões activas (incrementa tokenVersion). */
    @Transactional
    public void revokeSessions(String userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new UnauthorizedException("Sessão inválida."));
        user.setTokenVersion(user.getTokenVersion() + 1);
    }

    // ---------------------------------------------------------------------------
    // Recuperação de senha ("Esqueci a senha")
    // ---------------------------------------------------------------------------

    public record ResetRequested(boolean sent) {
    }

    public record ResetEmail(String subject, String text, String html) {
    }

    /** Espelha buildResetEmail — mesmo assunto, mesmo texto, mesmo HTML. */
    static ResetEmail buildResetEmail(String name, String link) {
        String subject = "Recuperação de senha — KIXIMA";
        String text = String.join("\n",
                "Olá " + name + ",", "",
                "Recebemos um pedido para redefinir a senha da sua conta KIXIMA.",
                "Clique no link abaixo para escolher uma nova senha (válido por 1 hora):", link, "",
                "Se não fez este pedido, ignore este email — a sua senha mantém-se.",
                "", "Equipe Kixima.");
        String html = "\n"
                + "    <div style=\"font-family:Arial,Helvetica,sans-serif;color:#1a1a1a;line-height:1.5\">\n"
                + "      <p>Olá <strong>" + name + "</strong>,</p>\n"
                + "      <p>Recebemos um pedido para redefinir a senha da sua conta KIXIMA.</p>\n"
                + "      <p style=\"margin:22px 0\">\n"
                + "        <a href=\"" + link + "\" style=\"background:#c1121f;color:#fff;text-decoration:none;padding:12px 22px;border-radius:8px;font-weight:600;display:inline-block\">Redefinir senha</a>\n"
                + "      </p>\n"
                + "      <p style=\"font-size:13px;color:#666\">O link é válido por 1 hora e só pode ser usado uma vez.</p>\n"
                + "      <p style=\"font-size:13px;color:#666\">Se não fez este pedido, ignore este email — a sua senha mantém-se.</p>\n"
                + "      <p>Equipe Kixima.</p>\n"
                + "    </div>";
        return new ResetEmail(subject, text, html);
    }

    /**
     * Pedido de recuperação. NUNCA revela se o email existe (anti-enumeração):
     * o controller devolve sempre a mesma resposta; aqui apenas não enviamos
     * nada quando a conta não existe/está inativa. O link usa APP_URL quando
     * definida, senão o endereço real do pedido, senão o valor por omissão do
     * Node (config.appUrl = http://localhost:4000). O envio segue por
     * {@link EmailDispatchService#dispatch} — o caminho em que uma falha fica
     * só no log (tal como notificationService.sendEmail), por isso
     * {@code sent=true} significa "entregue ao provider", não "recebido".
     */
    @Transactional(readOnly = true)
    public ResetRequested requestPasswordReset(String email, String baseUrl) {
        User user = userRepository.findByEmail(email == null ? "" : email.trim().toLowerCase()).orElse(null);
        if (user == null || !user.isActive()) return new ResetRequested(false);
        String token = jwtService.signPasswordReset(user.getId(), user.getTokenVersion());
        String base = !appUrl.isBlank() ? appUrl
                : (baseUrl != null && !baseUrl.isBlank() ? baseUrl : "http://localhost:4000");
        String link = base.replaceAll("/$", "") + "/recuperar/" + token;
        ResetEmail e = buildResetEmail(user.getName(), link);
        emailDispatchService.dispatch(user.getEmail(), e.subject(), e.text(), e.html());
        return new ResetRequested(true);
    }

    public ResetRequested requestPasswordReset(String email) {
        return requestPasswordReset(email, null);
    }

    public record ResetResult(String userId, String email) {
    }

    @Transactional
    public ResetResult resetPassword(String token, String newPassword) {
        // Espelha resetPasswordSchema (zod, `password: senha()`): a política SEM perfil
        // corre ANTES do token ser sequer olhado — uma senha curta é 422 mesmo com
        // um token inválido, e o envelope é o do validate() ("Dados inválidos." +
        // fieldErrors), nunca 401.
        String erroSchema = passwordPolicy.validar(newPassword);
        if (erroSchema != null) {
            throw new ValidationException("Dados inválidos.",
                    Map.of("formErrors", List.of(), "fieldErrors", Map.of("password", List.of(erroSchema))));
        }
        User user = verifyPasswordReset(token);
        String erro = passwordPolicy.validar(newPassword, user.getRole(), user.getEmail());
        if (erro != null) throw new ValidationException(erro);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setTokenVersion(user.getTokenVersion() + 1);
        return new ResetResult(user.getId(), user.getEmail());
    }

    private User verifyPasswordReset(String token) {
        Claims claims;
        try {
            claims = jwtService.verifyRaw(token);
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthorizedException("Link de recuperação inválido ou expirado. Peça um novo.");
        }
        if (!"pwreset".equals(claims.get("t")) || claims.getSubject() == null) {
            throw new UnauthorizedException("Link de recuperação inválido.");
        }
        User user = userRepository.findById(claims.getSubject()).orElse(null);
        Integer tv = claims.get("tv", Integer.class);
        if (user == null || user.getTokenVersion() != (tv == null ? 0 : tv)) {
            throw new UnauthorizedException("Este link de recuperação já foi utilizado ou expirou. Peça um novo.");
        }
        return user;
    }

    // --- 2FA (TOTP / EMAIL) ----------------------------------------------

    public record TotpStatus(boolean enabled, Instant enabledAt, String metodo, String emailIndisponivel, String email) {
    }

    @Transactional(readOnly = true)
    public TotpStatus totpStatus(String userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new UnauthorizedException("Sessão inválida."));
        boolean enabled = user.getTotpEnabledAt() != null;
        return new TotpStatus(enabled, user.getTotpEnabledAt(), enabled ? (user.getMfaMethod() != null ? user.getMfaMethod() : "TOTP") : null,
                // A interface precisa de saber se o email está mesmo a funcionar ANTES de
                // deixar ativar: sem isso, a pessoa ativava e ficava trancada fora.
                mfaEmailService.porqueNaoPodeUsarEmail(),
                user.getEmail() != null ? mfaEmailService.mascarar(user.getEmail()) : null);
    }

    // --- Ativação por EMAIL (método por omissão) --------------------------------
    /** Passo 1: envia um código para o email da pessoa. */
    @Transactional(noRollbackFor = BusinessRuleException.class)
    public MfaEmailService.Envio enviarCodigoAtivacao(String userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new UnauthorizedException("Sessão inválida."));
        if (user.getTotpEnabledAt() != null) throw new ConflictException("A verificação em dois passos já está ativa.");
        return mfaEmailService.enviarCodigo(user, "ativacao", false);
    }

    // --- Ativação por APP (TOTP) ------------------------------------------------

    public record TotpSetup(String secret, String otpauthUrl) {
    }

    @Transactional
    public TotpSetup setupTotp(String userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new UnauthorizedException("Sessão inválida."));
        if (user.getTotpEnabledAt() != null) throw new ConflictException("A verificação em dois passos já está ativa.");
        String secret = totpService.generateSecret();
        user.setTotpSecret(secret);
        user.setTotpEnabledAt(null);
        return new TotpSetup(secret, totpService.otpauthUrl(secret, user.getEmail()));
    }

    public record TotpEnableResult(boolean enabled, Instant enabledAt, String metodo) {
    }

    /**
     * Passo 2 (ambos os métodos): a pessoa prova que recebe os códigos — só
     * então a 2FA fica ativa. O método fica gravado, porque é ele que decide o
     * que lhe vai ser pedido no login.
     */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public TotpEnableResult enableTotp(String userId, String code) {
        User user = userRepository.findById(userId).orElseThrow(() -> new UnauthorizedException("Sessão inválida."));
        if (user.getTotpEnabledAt() != null) throw new ConflictException("A verificação em dois passos já está ativa.");

        // Há um código de email pendente → é uma ativação por email.
        if (user.getMfaCodeHash() != null) {
            String problema = mfaEmailService.confirmarCodigo(user, code);
            if (problema != null) throw new UnauthorizedException(problema);
            Instant now = Instant.now();
            user.setTotpEnabledAt(now);
            user.setMfaMethod("EMAIL");
            user.setTotpSecret(null);
            return new TotpEnableResult(true, now, "EMAIL");
        }

        if (user.getTotpSecret() == null) {
            throw new ConflictException("Inicie primeiro a ativação (pedir o código por email ou gerar o código QR).");
        }
        if (!totpService.verify(code, user.getTotpSecret())) {
            throw new UnauthorizedException(totpService.explicarFalha(code, user.getTotpSecret()));
        }
        Instant now = Instant.now();
        user.setTotpEnabledAt(now);
        user.setMfaMethod("TOTP");
        return new TotpEnableResult(true, now, "TOTP");
    }

    public record TotpDisableResult(boolean enabled) {
    }

    /** Desativar exige um código válido (impede desativação por sessão roubada). Com o método EMAIL o código tem de ser pedido primeiro. */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public TotpDisableResult disableTotp(String userId, String code) {
        User user = userRepository.findById(userId).orElseThrow(() -> new UnauthorizedException("Sessão inválida."));
        if (user.getTotpEnabledAt() == null) throw new ConflictException("A verificação em dois passos não está ativa.");
        String problema = confirmarSegundoFator(user, code);
        if (problema != null) throw new UnauthorizedException(problema);
        user.setTotpSecret(null);
        user.setTotpEnabledAt(null);
        user.setMfaMethod(null);
        user.setMfaCodeHash(null);
        user.setMfaCodeExpiraEm(null);
        return new TotpDisableResult(false);
    }

    /**
     * Confirma o segundo fator, seja qual for o método configurado.
     * Devolve null se serve, ou a razão pela qual não serve.
     */
    private String confirmarSegundoFator(User user, String code) {
        String metodo = user.getMfaMethod() != null ? user.getMfaMethod() : "TOTP";
        if ("EMAIL".equals(metodo)) {
            return mfaEmailService.confirmarCodigo(user, code);
        }
        if (!totpService.verify(code, user.getTotpSecret())) {
            return totpService.explicarFalha(code, user.getTotpSecret());
        }
        return null;
    }

    /** 2º passo do login: troca desafio + código pela sessão completa. */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public LoginResponse verify2fa(String challenge, String code) {
        User user = utilizadorDoDesafio(challenge);
        if (user.getTotpEnabledAt() == null) {
            throw new UnauthorizedException("Esta conta não tem verificação em dois passos. Volte a iniciar sessão.");
        }
        String problema = confirmarSegundoFator(user, code);
        if (problema != null) throw new UnauthorizedException(problema);
        return buildSession(user);
    }

    private User utilizadorDoDesafio(String challenge) {
        Claims claims;
        try {
            claims = jwtService.verifyRaw(challenge);
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthorizedException("Desafio expirado — volte a iniciar sessão.");
        }
        if (!"2fa".equals(claims.get("t")) || claims.getSubject() == null) {
            throw new UnauthorizedException("Desafio inválido — volte a iniciar sessão.");
        }
        User user = userRepository.findByIdWithCompany(claims.getSubject()).orElse(null);
        Integer tv = claims.get("tv", Integer.class);
        if (user == null || !user.isActive() || user.getTokenVersion() != (tv == null ? 0 : tv)) {
            throw new UnauthorizedException("Sessão inválida — volte a iniciar sessão.");
        }
        return user;
    }

    /** Reenvio a partir do ecrã de login (ainda sem sessão) — só com um desafio válido. */
    @Transactional(noRollbackFor = BusinessRuleException.class)
    public MfaEmailService.Envio reenviarCodigoDoDesafio(String challenge) {
        User user = utilizadorDoDesafio(challenge);
        return reenviarCodigo(user);
    }

    /** Reenvio já dentro da sessão (para desativar a 2FA por email). */
    @Transactional(noRollbackFor = BusinessRuleException.class)
    public MfaEmailService.Envio reenviarCodigo(String userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new UnauthorizedException("Sessão inválida."));
        return reenviarCodigo(user);
    }

    /** Pede um código novo para uma conta que JÁ tem a 2FA por email. */
    private MfaEmailService.Envio reenviarCodigo(User user) {
        String metodo = user.getMfaMethod() != null ? user.getMfaMethod() : "TOTP";
        if (!"EMAIL".equals(metodo)) {
            throw new ConflictException("Esta conta usa a app de autenticação — o código é gerado no telemóvel.");
        }
        return mfaEmailService.enviarCodigo(user);
    }
}
