package ao.kixima.auth;

import ao.kixima.auth.dto.RegisterRequest;
import ao.kixima.common.error.BusinessRuleException;
import ao.kixima.common.error.ConflictException;
import ao.kixima.common.error.ValidationException;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyPlan;
import ao.kixima.company.CompanyRepository;
import ao.kixima.company.CompanySize;
import ao.kixima.company.CompanyStatus;
import ao.kixima.company.CompanyType;
import ao.kixima.notification.EmailDispatchService;
import ao.kixima.plan.PlanService;
import ao.kixima.security.JwtService;
import ao.kixima.security.PersonaRole;
import ao.kixima.user.PasswordPolicy;
import ao.kixima.user.User;
import ao.kixima.user.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import java.time.Instant;
import java.util.UUID;

/**
 * Registo em dois passos. Aqui só se cria a CONTA: o tipo de empresa e o administrador (nome, email profissional e
 * senha). A empresa nasce em RASCUNHO — sem nome nem NIF, que são pedidos no perfil — e a conta só entra depois de
 * confirmar o email. Os dados e os documentos da empresa são o assunto de {@link ao.kixima.company.OnboardingService}.
 *
 * A conta de utilizador e a empresa continuam separadas: o utilizador é COMPANY_ADMIN da empresa que acabou de abrir.
 */
@Service
public class RegistoService {

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final JwtService jwtService;
    private final PlanService planService;
    private final EmailDispatchService emailDispatchService;
    private final String appUrl;

    public RegistoService(UserRepository userRepository, CompanyRepository companyRepository, PasswordEncoder passwordEncoder,
                          PasswordPolicy passwordPolicy, JwtService jwtService, PlanService planService,
                          EmailDispatchService emailDispatchService, @Value("${kixima.app-url:}") String appUrl) {
        this.userRepository = userRepository;
        this.companyRepository = companyRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.jwtService = jwtService;
        this.planService = planService;
        this.emailDispatchService = emailDispatchService;
        this.appUrl = appUrl == null ? "" : appUrl;
    }

    public record Registado(String userId, String email, CompanyType type) {
    }

    @Transactional
    public Registado registar(RegisterRequest body, String baseUrl) {
        CompanyType type;
        try {
            type = CompanyType.valueOf(body.type());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("type: use CLIENTE ou FORNECEDOR.");
        }
        if (!Boolean.TRUE.equals(body.termsAccepted())) {
            throw new ValidationException("É necessário aceitar os Termos de Uso e a Política de Privacidade.");
        }
        String email = body.email().trim().toLowerCase();
        String erroSenha = passwordPolicy.validar(body.password(), PersonaRole.COMPANY_ADMIN, email);
        if (erroSenha != null) throw new ValidationException(erroSenha);
        if (userRepository.findByEmail(email).isPresent()) {
            throw new ConflictException("Já existe uma conta com este email. Se ainda não a confirmou, peça um novo email de confirmação.");
        }

        Instant agora = Instant.now();
        // Empresa em rascunho: nome e NIF por preencher (nunca valores inventados); o email da conta serve de contacto
        // inicial e o utilizador pode alterá-lo no perfil.
        Company company = new Company(UUID.randomUUID().toString(), null, null, type, email, null, null, null, agora);
        company.setStatus(CompanyStatus.RASCUNHO);
        company.setTermsAcceptedAt(agora);
        company.setSize(CompanySize.PEQUENA);
        company.setPlan(CompanyPlan.BASICO);
        company.setSearchRank(planService.rankDoPlano(CompanyPlan.BASICO));
        companyRepository.save(company);

        User admin = new User(UUID.randomUUID().toString(), body.name().trim(), email, passwordEncoder.encode(body.password()),
                PersonaRole.COMPANY_ADMIN, company.getId(), true, agora, agora);
        admin.setEmailVerifiedAt(null); // só entra depois de confirmar o email
        userRepository.save(admin);

        enviarConfirmacao(admin, baseUrl);
        return new Registado(admin.getId(), email, type);
    }

    /** Confirma o email a partir do link recebido. Idempotente: repetir o link não faz mal. */
    @Transactional
    public String confirmarEmail(String token) {
        Claims claims;
        try {
            claims = jwtService.verifyRaw(token);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessRuleException("Link de confirmação inválido ou expirado. Peça um novo email de confirmação.");
        }
        if (!"emailverify".equals(claims.get("t")) || claims.getSubject() == null) {
            throw new BusinessRuleException("Link de confirmação inválido. Peça um novo email de confirmação.");
        }
        User user = userRepository.findById(claims.getSubject())
                .orElseThrow(() -> new BusinessRuleException("Link de confirmação inválido. Peça um novo email de confirmação."));
        if (!user.isEmailVerified()) user.setEmailVerifiedAt(Instant.now());
        return user.getEmail();
    }

    /** Novo email de confirmação. Sem revelar se a conta existe: só envia se existir, estiver activa e por confirmar. */
    @Transactional(readOnly = true)
    public void reenviarConfirmacao(String email, String baseUrl) {
        User user = userRepository.findByEmail(email == null ? "" : email.trim().toLowerCase()).orElse(null);
        if (user == null || !user.isActive() || user.isEmailVerified()) return;
        enviarConfirmacao(user, baseUrl);
    }

    private void enviarConfirmacao(User user, String baseUrl) {
        String base = !appUrl.isBlank() ? appUrl : (baseUrl != null && !baseUrl.isBlank() ? baseUrl : "http://localhost:4000");
        String link = base.replaceAll("/$", "") + "/verificar-email/" + jwtService.signEmailVerification(user.getId());
        Email e = construirEmail(user.getName(), link);
        emailDispatchService.dispatch(user.getEmail(), e.subject(), e.text(), e.html());
    }

    record Email(String subject, String text, String html) {
    }

    static Email construirEmail(String nome, String link) {
        String subject = "Confirme o seu email — KIXIMA";
        String text = String.join("\n",
                "Olá " + nome + ",", "",
                "Obrigado por abrir uma conta na KIXIMA.",
                "Confirme o seu email no link abaixo (válido por 48 horas) para entrar e completar o perfil da empresa:", link, "",
                "Se não foi você, ignore este email — a conta não fica activa sem esta confirmação.",
                "", "Equipe Kixima.");
        String seguro = HtmlUtils.htmlEscape(nome);
        String html = "\n"
                + "    <div style=\"font-family:Arial,Helvetica,sans-serif;color:#1a1a1a;line-height:1.5\">\n"
                + "      <p>Olá <strong>" + seguro + "</strong>,</p>\n"
                + "      <p>Obrigado por abrir uma conta na KIXIMA. Confirme o seu email para entrar e completar o perfil da empresa.</p>\n"
                + "      <p style=\"margin:22px 0\">\n"
                + "        <a href=\"" + link + "\" style=\"background:#c1121f;color:#fff;text-decoration:none;padding:12px 22px;border-radius:8px;font-weight:600;display:inline-block\">Confirmar email</a>\n"
                + "      </p>\n"
                + "      <p style=\"font-size:13px;color:#666\">O link é válido por 48 horas.</p>\n"
                + "      <p style=\"font-size:13px;color:#666\">Se não foi você, ignore este email — a conta não fica activa sem esta confirmação.</p>\n"
                + "      <p>Equipe Kixima.</p>\n"
                + "    </div>";
        return new Email(subject, text, html);
    }
}
