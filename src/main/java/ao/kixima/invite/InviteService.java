package ao.kixima.invite;

import ao.kixima.common.error.BusinessRuleException;
import ao.kixima.common.error.ConflictException;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.common.error.PlanRequiredException;
import ao.kixima.common.error.UnauthorizedException;
import ao.kixima.common.error.ValidationException;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyRepository;
import ao.kixima.company.CompanyType;
import ao.kixima.invite.dto.CompanyUserDto;
import ao.kixima.invite.dto.InviteCreatedDto;
import ao.kixima.invite.dto.InviteDto;
import ao.kixima.invite.dto.ResolvedInviteDto;
import ao.kixima.notification.EmailDispatchService;
import ao.kixima.plan.PlanLimit;
import ao.kixima.plan.PlanService;
import ao.kixima.plan.SubscriptionState;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Espelha o troço de "Convites de utilizadores" de
 * backend/src/services/companyService.js + backend/src/controllers/companyController.js
 * — convite self-service com aprovação do Company Admin, e a gestão da
 * equipa que o acompanha (activar/bloquear/remover).
 *
 * O convite de assessor ADMIN_SISTEMA (adminService.js:
 * createAdminInvite/resendAdminInvite/...) usa a mesma tabela mas vive em
 * {@link ao.kixima.admin.AdminService}, com controller próprio, como no Node.
 */
@Service
public class InviteService {

    private static final int INVITE_TTL_DAYS = 7;
    private static final Map<CompanyType, Set<PersonaRole>> INVITABLE_ROLES = Map.of(
            CompanyType.CLIENTE, Set.of(PersonaRole.COMPRADOR, PersonaRole.FINANCEIRO),
            CompanyType.FORNECEDOR, Set.of(PersonaRole.COMPRADOR, PersonaRole.FORNECEDOR, PersonaRole.FINANCEIRO)
    );

    private final EmployeeInviteRepository inviteRepository;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final JwtService jwtService;
    private final PlanService planService;
    private final EmailDispatchService emailDispatchService;
    private final String appUrl;

    public InviteService(EmployeeInviteRepository inviteRepository, CompanyRepository companyRepository,
                          UserRepository userRepository, PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy,
                          JwtService jwtService, PlanService planService, EmailDispatchService emailDispatchService,
                          @Value("${kixima.app-url:}") String appUrl) {
        this.inviteRepository = inviteRepository;
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.jwtService = jwtService;
        this.planService = planService;
        this.emailDispatchService = emailDispatchService;
        this.appUrl = appUrl;
    }

    // --- Criação/gestão pelo Company Admin -----------------------------

    @Transactional
    public InviteCreatedDto criar(String companyId, String roleBruto, String name, String email, String baseUrl) {
        Company company = companyRepository.findById(companyId).orElseThrow(() -> new NotFoundException("Empresa"));
        PersonaRole role = papelValido(roleBruto);
        Set<PersonaRole> permitidos = INVITABLE_ROLES.getOrDefault(company.getType(), Set.of());
        if (!permitidos.contains(role)) {
            throw new BusinessRuleException("Este perfil não pode ser convidado para este tipo de empresa.");
        }
        assertLugaresDisponiveis(company);

        String normEmail = normalizar(email);
        if (userRepository.findByEmail(normEmail).isPresent()) {
            throw new ConflictException("Já existe uma conta com este email.");
        }
        Instant agora = Instant.now();
        Instant expiresAt = agora.plus(Duration.ofDays(INVITE_TTL_DAYS));
        EmployeeInvite created = new EmployeeInvite(UUID.randomUUID().toString(), companyId, name.trim(), normEmail,
                role, "pending", expiresAt, null, agora);
        inviteRepository.save(created);
        String token = jwtService.signInvite(companyId, role.name(), created.getId());
        created.setToken(token);

        enviarEmailDeConvite(created, company, baseUrl);
        return new InviteCreatedDto(created.getId(), created.getName(), created.getEmail(), created.getRole().name(),
                created.getStatus().name(), created.getExpiresAt(), created.getAcceptedAt(), created.getCreatedAt(),
                company.getName());
    }

    /**
     * Convite de FUNDAÇÃO: o único caso em que o convidado é o próprio
     * primeiro Company Admin — a empresa acaba de nascer (ver
     * ao.kixima.supplierdev.SupplierDevService#approve) e ainda não tem
     * ninguém para o convidar de dentro da plataforma, ao contrário do
     * convite normal (sempre enviado por um Company Admin já existente).
     * Sem INVITABLE_ROLES a validar (é sempre COMPANY_ADMIN) nem lugares
     * de plano a verificar (o primeiro lugar de uma empresa nova nunca
     * está esgotado).
     */
    @Transactional
    public InviteCreatedDto criarConviteDeFundacao(Company company, String name, String email, String createdById, String baseUrl) {
        String normEmail = normalizar(email);
        if (userRepository.findByEmail(normEmail).isPresent()) {
            throw new ConflictException("Já existe uma conta com este email.");
        }
        Instant agora = Instant.now();
        Instant expiresAt = agora.plus(Duration.ofDays(INVITE_TTL_DAYS));
        EmployeeInvite created = new EmployeeInvite(UUID.randomUUID().toString(), company.getId(), name.trim(),
                normEmail, PersonaRole.COMPANY_ADMIN, "pending", expiresAt, createdById, agora);
        inviteRepository.save(created);
        String token = jwtService.signInvite(company.getId(), PersonaRole.COMPANY_ADMIN.name(), created.getId());
        created.setToken(token);

        enviarEmailDeConviteDeFundacao(created, company, baseUrl);
        return new InviteCreatedDto(created.getId(), created.getName(), created.getEmail(), created.getRole().name(),
                created.getStatus().name(), created.getExpiresAt(), created.getAcceptedAt(), created.getCreatedAt(),
                company.getName());
    }

    private PersonaRole papelValido(String roleBruto) {
        try {
            return PersonaRole.valueOf(roleBruto);
        } catch (Exception e) {
            throw new ValidationException("Perfil de convite inválido.");
        }
    }

    /**
     * Há lugar livre no plano para mais uma pessoa? O limite é do PLANO, e
     * a conta inclui os convites por aceitar — senão bastava enviar todos
     * de uma vez para o limite não valer nada.
     */
    private void assertLugaresDisponiveis(Company company) {
        if (planService.estadoSubscricao(company) == SubscriptionState.RESTRITA) {
            throw new PlanRequiredException(
                    "A subscrição da sua empresa está vencida há mais de " + planService.gracePeriodDays() + " dias. "
                            + "Regularize o pagamento para poder convidar mais utilizadores.",
                    planService.normalizarPlano(company.getPlan()).name());
        }
        long ativos = userRepository.countByCompanyIdAndActiveTrue(company.getId());
        long convitesAbertos = inviteRepository.countByCompanyIdAndStatusAndExpiresAtAfter(
                company.getId(), InviteStatus.PENDENTE, Instant.now());
        planService.assertLimite(company, PlanLimit.LUGARES_INCLUIDOS, (int) (ativos + convitesAbertos), "lugares (utilizadores)");
    }

    @Transactional(readOnly = true)
    public List<InviteDto> listar(String companyId) {
        return inviteRepository.findByCompanyIdOrderByCreatedAtDesc(companyId).stream().map(this::toDtoComExpiracao).toList();
    }

    @Transactional
    public InviteDto reenviar(String companyId, String inviteId, String baseUrl) {
        EmployeeInvite invite = convitePertencente(companyId, inviteId);
        if (invite.getStatus() == InviteStatus.ACEITO) throw new BusinessRuleException("Este convite já foi aceite.");
        Company company = companyRepository.findById(companyId).orElseThrow(() -> new NotFoundException("Empresa"));
        Instant expiresAt = Instant.now().plus(Duration.ofDays(INVITE_TTL_DAYS));
        String token = jwtService.signInvite(companyId, invite.getRole().name(), invite.getId());
        invite.setToken(token);
        invite.setStatus(InviteStatus.PENDENTE);
        invite.setExpiresAt(expiresAt);
        invite.setAcceptedAt(null);
        enviarEmailDeConvite(invite, company, baseUrl);
        return toDto(invite);
    }

    @Transactional
    public InviteDto cancelar(String companyId, String inviteId) {
        EmployeeInvite invite = convitePertencente(companyId, inviteId);
        if (invite.getStatus() == InviteStatus.ACEITO) throw new BusinessRuleException("Este convite já foi aceite.");
        invite.setStatus(InviteStatus.CANCELADO);
        return toDto(invite);
    }

    private EmployeeInvite convitePertencente(String companyId, String inviteId) {
        return inviteRepository.findByIdAndCompanyId(inviteId, companyId).orElseThrow(() -> new NotFoundException("Convite"));
    }

    // --- Público: resolução/aceitação -----------------------------------

    public record InviteClaims(String companyId, PersonaRole role, String inviteId) {
    }

    private InviteClaims verificar(String token) {
        Claims claims;
        try {
            claims = jwtService.verifyRaw(token);
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthorizedException("Convite inválido ou expirado.");
        }
        String companyId = claims.get("companyId", String.class);
        String roleBruto = claims.get("role", String.class);
        if (!"invite".equals(claims.get("t")) || companyId == null || roleBruto == null) {
            throw new UnauthorizedException("Convite inválido.");
        }
        return new InviteClaims(companyId, papelValido(roleBruto), claims.get("iid", String.class));
    }

    @Transactional(readOnly = true)
    public ResolvedInviteDto resolver(String token) {
        InviteClaims c = verificar(token);
        Company company = companyRepository.findById(c.companyId()).orElseThrow(() -> new NotFoundException("Empresa"));
        String name = null;
        String email = null;
        if (c.inviteId() != null) {
            EmployeeInvite invite = conviteValidoParaToken(c.inviteId(), c.companyId());
            name = invite.getName();
            email = invite.getEmail();
        }
        return new ResolvedInviteDto(company.getName(), company.getType().name(), c.role().name(), name, email);
    }

    private EmployeeInvite conviteValidoParaToken(String inviteId, String companyId) {
        EmployeeInvite invite = inviteRepository.findByIdAndCompanyId(inviteId, companyId)
                .orElseThrow(() -> new NotFoundException("Convite"));
        if (invite.getStatus() == InviteStatus.CANCELADO) throw new BusinessRuleException("Este convite foi cancelado.");
        if (invite.getStatus() == InviteStatus.ACEITO) throw new BusinessRuleException("Este convite já foi aceite.");
        if (invite.getExpiresAt() != null && invite.getExpiresAt().isBefore(Instant.now())) {
            throw new BusinessRuleException("Este convite expirou.");
        }
        return invite;
    }

    @Transactional
    public CompanyUserDto aceitar(String token, String nomeDoFormulario, String emailDoFormulario, String password) {
        InviteClaims c = verificar(token);
        companyRepository.findById(c.companyId()).orElseThrow(() -> new NotFoundException("Empresa"));

        String finalName = nomeDoFormulario;
        String finalEmail = emailDoFormulario;
        EmployeeInvite invite = null;
        if (c.inviteId() != null) {
            invite = conviteValidoParaToken(c.inviteId(), c.companyId());
            finalName = invite.getName();
            finalEmail = invite.getEmail();
        }
        finalEmail = normalizar(finalEmail);
        if (finalName == null || finalName.isBlank() || finalEmail.isBlank()) {
            throw new BusinessRuleException("Dados do convite incompletos.");
        }
        if (userRepository.findByEmail(finalEmail).isPresent()) {
            throw new ConflictException("Já existe uma conta com este email.");
        }
        // O perfil do convidado vem do CONVITE, não do que ele envia — um convite
        // para Financeiro exige a mesma política de senha que qualquer outra
        // conta que autoriza pagamentos.
        String erroSenha = passwordPolicy.validar(password, c.role(), finalEmail);
        if (erroSenha != null) throw new ValidationException(erroSenha);

        // Convites normais ficam inativos até o Company Admin aceitar — o
        // primeiro Company Admin de uma empresa (cadastro, CompanyService.
        // registerCompany) É quem aprova, por isso nasce ativo.
        boolean active = c.role() == PersonaRole.COMPANY_ADMIN;
        User user = new User(UUID.randomUUID().toString(), finalName, finalEmail, passwordEncoder.encode(password),
                c.role(), c.companyId(), active, Instant.now(), Instant.now());
        userRepository.save(user);
        if (invite != null) {
            invite.setStatus(InviteStatus.ACEITO);
            invite.setAcceptedAt(Instant.now());
        }
        return toDto(user);
    }

    // --- Equipa (Company Admin) ------------------------------------------

    @Transactional(readOnly = true)
    public List<CompanyUserDto> listarUtilizadores(String companyId) {
        return userRepository.findByCompanyIdOrderByActiveAscCreatedAtDesc(companyId).stream().map(this::toDto).toList();
    }

    @Transactional
    public CompanyUserDto ativar(String companyId, String userId) {
        User user = utilizadorDaEmpresa(companyId, userId);
        user.setActive(true);
        return toDto(user);
    }

    /** Espelha `return { id: userId }` — o Node NÃO devolve o utilizador removido, só o id (companyController usa isto tal como está: `entityRef: result?.email ?? null` é sempre null aqui). */
    @Transactional
    public String remover(String companyId, String userId) {
        User user = utilizadorDaEmpresa(companyId, userId);
        if (user.getRole() == PersonaRole.COMPANY_ADMIN) {
            throw new BusinessRuleException("Não é possível remover o administrador da empresa.");
        }
        userRepository.delete(user);
        return userId;
    }

    @Transactional
    public CompanyUserDto definirEstado(String companyId, String userId, boolean active, String actingUserId) {
        User user = utilizadorDaEmpresa(companyId, userId);
        if (userId.equals(actingUserId)) throw new BusinessRuleException("Não pode bloquear a própria conta.");
        if (user.getRole() == PersonaRole.COMPANY_ADMIN) {
            throw new BusinessRuleException("Não é possível bloquear o administrador da empresa.");
        }
        user.setActive(active);
        return toDto(user);
    }

    private User utilizadorDaEmpresa(String companyId, String userId) {
        return userRepository.findByIdAndCompanyId(userId, companyId).orElseThrow(() -> new NotFoundException("Utilizador"));
    }

    // --- Email ------------------------------------------------------------

    private void enviarEmailDeConvite(EmployeeInvite invite, Company company, String baseUrlDoPedido) {
        String base = !appUrl.isBlank() ? appUrl
                : (baseUrlDoPedido != null && !baseUrlDoPedido.isBlank() ? baseUrlDoPedido : "http://localhost:4000");
        String link = base.replaceAll("/$", "") + "/convite/" + invite.getToken();
        String texto = "Olá " + invite.getName() + ",\n\n"
                + "A empresa " + company.getName() + " convidou você para acessar a plataforma Kixima.\n\n"
                + "Clique no link abaixo para completar o seu cadastro:\n" + link + "\n\n"
                + "Este link é válido por " + INVITE_TTL_DAYS + " dias.\n\n"
                + "Equipe Kixima.";
        emailDispatchService.dispatch(invite.getEmail(), "Convite para acessar a plataforma Kixima", texto);
    }

    private void enviarEmailDeConviteDeFundacao(EmployeeInvite invite, Company company, String baseUrlDoPedido) {
        String base = !appUrl.isBlank() ? appUrl
                : (baseUrlDoPedido != null && !baseUrlDoPedido.isBlank() ? baseUrlDoPedido : "http://localhost:4000");
        String link = base.replaceAll("/$", "") + "/convite/" + invite.getToken();
        String texto = "Olá " + invite.getName() + ",\n\n"
                + "A candidatura de " + company.getName() + " ao programa Supplier Development foi aprovada.\n"
                + "Falta um passo para começar a usar a plataforma: defina a senha da sua conta de administrador.\n\n"
                + "Clique no link abaixo:\n" + link + "\n\n"
                + "Este link é válido por " + INVITE_TTL_DAYS + " dias.\n\n"
                + "Equipe Kixima.";
        emailDispatchService.dispatch(invite.getEmail(), "A sua empresa foi aprovada na KIXIMA — crie a sua conta", texto);
    }

    // --- Auxiliares ---------------------------------------------------

    private String normalizar(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private InviteDto toDto(EmployeeInvite i) {
        return new InviteDto(i.getId(), i.getName(), i.getEmail(), i.getRole().name(), i.getStatus().name(),
                i.getExpiresAt(), i.getAcceptedAt(), i.getCreatedAt());
    }

    /** Marca como EXPIRADO na LEITURA os pendentes vencidos — nunca escreve o estado computado na base. */
    private InviteDto toDtoComExpiracao(EmployeeInvite i) {
        String status = i.getStatus() == InviteStatus.PENDENTE && i.getExpiresAt() != null && i.getExpiresAt().isBefore(Instant.now())
                ? InviteStatus.EXPIRADO.name() : i.getStatus().name();
        return new InviteDto(i.getId(), i.getName(), i.getEmail(), i.getRole().name(), status,
                i.getExpiresAt(), i.getAcceptedAt(), i.getCreatedAt());
    }

    private CompanyUserDto toDto(User u) {
        return new CompanyUserDto(u.getId(), u.getName(), u.getEmail(), u.getRole().name(), u.isActive(), u.getCreatedAt());
    }
}
