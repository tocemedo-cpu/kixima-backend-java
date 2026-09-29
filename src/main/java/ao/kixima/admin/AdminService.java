package ao.kixima.admin;

import ao.kixima.admin.dto.AdminInviteDto;
import ao.kixima.admin.dto.AdminUserDto;
import ao.kixima.admin.dto.ResolvedAdminInviteDto;
import ao.kixima.common.error.BusinessRuleException;
import ao.kixima.common.error.ConflictException;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.common.error.ValidationException;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyRepository;
import ao.kixima.company.SupplierToKiximaPolicy;
import ao.kixima.company.SupplierToKiximaPolicyRepository;
import ao.kixima.invite.EmployeeInvite;
import ao.kixima.invite.EmployeeInviteRepository;
import ao.kixima.invite.InviteStatus;
import ao.kixima.notification.EmailDispatchService;
import ao.kixima.payment.Payment;
import ao.kixima.payment.PaymentRepository;
import ao.kixima.po.PurchaseOrder;
import ao.kixima.po.PurchaseOrderRepository;
import ao.kixima.security.AdminArea;
import ao.kixima.security.PersonaRole;
import ao.kixima.support.SupportTicket;
import ao.kixima.support.SupportTicketRepository;
import ao.kixima.user.PasswordPolicy;
import ao.kixima.user.User;
import ao.kixima.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Espelha backend/src/services/adminService.js — utilizadores da plataforma
 * (bloquear/desbloquear, áreas), convites de assessor ADMIN_SISTEMA e o feed
 * de actividades de todo o sistema. As taxas da plataforma vivem em
 * {@link ao.kixima.payment.PlatformFeeService} (Lacunas A.3).
 *
 * O convite de assessor reutiliza a MESMA tabela e ciclo de vida do convite
 * de funcionário ({@link EmployeeInvite}); o que muda é o token — aleatório,
 * opaco, sem nada lá dentro — e o facto de nunca se confiar em áreas vindas
 * do pedido do próprio assessor.
 */
@Service
public class AdminService {

    static final int ADMIN_INVITE_TTL_DIAS = 7;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final EmployeeInviteRepository inviteRepository;
    private final CompanyRepository companyRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PaymentRepository paymentRepository;
    private final SupportTicketRepository supportTicketRepository;
    private final SupplierToKiximaPolicyRepository policyRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final EmailDispatchService emailDispatchService;
    private final String appUrl;

    public AdminService(UserRepository userRepository, EmployeeInviteRepository inviteRepository,
                        CompanyRepository companyRepository, PurchaseOrderRepository purchaseOrderRepository,
                        PaymentRepository paymentRepository, SupportTicketRepository supportTicketRepository,
                        SupplierToKiximaPolicyRepository policyRepository, PasswordEncoder passwordEncoder,
                        PasswordPolicy passwordPolicy, EmailDispatchService emailDispatchService,
                        @Value("${kixima.app-url:}") String appUrl) {
        this.userRepository = userRepository;
        this.inviteRepository = inviteRepository;
        this.companyRepository = companyRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.paymentRepository = paymentRepository;
        this.supportTicketRepository = supportTicketRepository;
        this.policyRepository = policyRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.emailDispatchService = emailDispatchService;
        this.appUrl = appUrl == null ? "" : appUrl;
    }

    private static String pretty(Object s) {
        return s == null ? "" : String.valueOf(s).replace("_", " ").toLowerCase();
    }

    // --- Utilizadores ----------------------------------------------------

    @Transactional(readOnly = true)
    public List<AdminUserDto> listUsers() {
        return userRepository.findTodosOrderByActiveAscCreatedAtDesc().stream()
                .map(u -> new AdminUserDto(u.getId(), u.getName(), u.getEmail(), u.getRole().name(), u.isActive(),
                        u.getCompany() == null ? null : u.getCompany().getName(), u.getCreatedAt(), u.getAdminAreas()))
                .toList();
    }

    @Transactional
    public Map<String, Object> setUserStatus(String id, boolean active, String actingUserId) {
        User user = userRepository.findById(id).orElseThrow(() -> new NotFoundException("Utilizador"));
        if (id.equals(actingUserId)) throw new BusinessRuleException("Não pode bloquear a própria conta.");
        user.setActive(active);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", user.getId());
        m.put("name", user.getName());
        m.put("active", user.isActive());
        m.put("role", user.getRole().name());
        return m;
    }

    @Transactional
    public Map<String, Object> setUserAreas(String id, List<String> areas, String actingUserId) {
        User user = userRepository.findById(id).orElseThrow(() -> new NotFoundException("Utilizador"));
        if (user.getRole() != PersonaRole.ADMIN_SISTEMA) throw new BusinessRuleException("Áreas só se aplicam a Admin do Sistema.");
        if (id.equals(actingUserId)) throw new BusinessRuleException("Não pode alterar as próprias áreas.");
        user.setAdminAreas(new ArrayList<>(areas));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", user.getId());
        m.put("name", user.getName());
        m.put("role", user.getRole().name());
        m.put("adminAreas", user.getAdminAreas());
        return m;
    }

    // --- Convites de assessor -------------------------------------------

    /** 32 bytes aleatórios em hex — nada assinado, nada dentro: a base é a única fonte de verdade. */
    static String gerarTokenDeConvite() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static List<String> areasValidadas(List<String> adminAreas) {
        List<String> areas = new ArrayList<>(new LinkedHashSet<>(adminAreas == null ? List.of() : adminAreas));
        List<String> invalidas = areas.stream().filter(a -> !AdminArea.AREAS_ADMIN.contains(a)).toList();
        if (!invalidas.isEmpty()) throw new BusinessRuleException("Área desconhecida: " + String.join(", ", invalidas) + ".");
        if (areas.isEmpty()) throw new BusinessRuleException("Selecione pelo menos uma área administrativa.");
        return areas;
    }

    @Transactional
    public AdminInviteDto createAdminInvite(String name, String email, List<String> adminAreas, String createdById, String baseUrl) {
        List<String> areas = areasValidadas(adminAreas);
        String normEmail = email == null ? "" : email.trim().toLowerCase();
        if (userRepository.findByEmail(normEmail).isPresent()) throw new ConflictException("Já existe uma conta com este email.");
        Instant agora = Instant.now();
        EmployeeInvite invite = new EmployeeInvite(UUID.randomUUID().toString(), null, name.trim(), normEmail,
                PersonaRole.ADMIN_SISTEMA, gerarTokenDeConvite(), agora.plus(Duration.ofDays(ADMIN_INVITE_TTL_DIAS)), createdById, agora);
        invite.setAdminAreas(areas);
        inviteRepository.save(invite);
        sendAdminInviteEmail(invite, baseUrl);
        return AdminInviteDto.de(invite);
    }

    @Transactional(readOnly = true)
    public List<AdminInviteDto> listAdminInvites() {
        return inviteRepository.findByRoleOrderByCreatedAtDesc(PersonaRole.ADMIN_SISTEMA).stream()
                .map(AdminInviteDto::comExpiracao).toList();
    }

    private EmployeeInvite getAdminInvite(String inviteId) {
        EmployeeInvite invite = inviteRepository.findById(inviteId).orElse(null);
        if (invite == null || invite.getRole() != PersonaRole.ADMIN_SISTEMA) throw new NotFoundException("Convite");
        return invite;
    }

    @Transactional
    public AdminInviteDto resendAdminInvite(String inviteId, String baseUrl) {
        EmployeeInvite invite = getAdminInvite(inviteId);
        if (invite.getStatus() == InviteStatus.ACEITO) throw new BusinessRuleException("Este convite já foi aceite.");
        invite.setToken(gerarTokenDeConvite());
        invite.setStatus(InviteStatus.PENDENTE);
        invite.setExpiresAt(Instant.now().plus(Duration.ofDays(ADMIN_INVITE_TTL_DIAS)));
        invite.setAcceptedAt(null);
        sendAdminInviteEmail(invite, baseUrl);
        return AdminInviteDto.de(invite);
    }

    @Transactional
    public AdminInviteDto cancelAdminInvite(String inviteId) {
        EmployeeInvite invite = getAdminInvite(inviteId);
        if (invite.getStatus() == InviteStatus.ACEITO) throw new BusinessRuleException("Este convite já foi aceite.");
        invite.setStatus(InviteStatus.CANCELADO);
        return AdminInviteDto.de(invite);
    }

    /** Um token que não existe é um erro de negócio (400), não uma excepção crua nem um 404 que confirme o que existe. */
    private EmployeeInvite getInviteForAdminToken(String token) {
        EmployeeInvite invite = token == null || token.isBlank() ? null : inviteRepository.findByToken(token).orElse(null);
        if (invite == null || invite.getRole() != PersonaRole.ADMIN_SISTEMA) throw new BusinessRuleException("Convite inválido.");
        if (invite.getStatus() == InviteStatus.CANCELADO) throw new BusinessRuleException("Este convite foi cancelado.");
        if (invite.getStatus() == InviteStatus.ACEITO) throw new BusinessRuleException("Este convite já foi utilizado.");
        if (invite.getExpiresAt() != null && invite.getExpiresAt().isBefore(Instant.now())) throw new BusinessRuleException("Este convite expirou.");
        return invite;
    }

    @Transactional(readOnly = true)
    public ResolvedAdminInviteDto resolveAdminInvite(String token) {
        EmployeeInvite invite = getInviteForAdminToken(token);
        return new ResolvedAdminInviteDto(invite.getName(), invite.getEmail(), invite.getAdminAreas());
    }

    /**
     * Aceitação: a conta nasce ATIVA (o Super Admin já decidiu ao convidar) e
     * com as áreas DO CONVITE — nunca as do pedido, que o controller nem lê.
     */
    @Transactional
    public User acceptAdminInvite(String token, String password) {
        EmployeeInvite invite = getInviteForAdminToken(token);
        if (userRepository.findByEmail(invite.getEmail()).isPresent()) throw new ConflictException("Já existe uma conta com este email.");
        String erroSenha = passwordPolicy.validar(password, PersonaRole.ADMIN_SISTEMA, invite.getEmail());
        if (erroSenha != null) throw new ValidationException(erroSenha);
        Instant agora = Instant.now();
        User user = new User(UUID.randomUUID().toString(), invite.getName(), invite.getEmail(), passwordEncoder.encode(password),
                PersonaRole.ADMIN_SISTEMA, null, true, agora, agora);
        user.setAdminAreas(new ArrayList<>(invite.getAdminAreas()));
        userRepository.save(user);
        invite.setStatus(InviteStatus.ACEITO);
        invite.setAcceptedAt(agora);
        return user;
    }

    public static Map<String, Object> utilizadorAceite(User user) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", user.getId());
        m.put("name", user.getName());
        m.put("email", user.getEmail());
        m.put("role", user.getRole().name());
        m.put("adminAreas", user.getAdminAreas());
        m.put("active", user.isActive());
        m.put("createdAt", user.getCreatedAt());
        return m;
    }

    private void sendAdminInviteEmail(EmployeeInvite invite, String baseUrl) {
        String base = !appUrl.isBlank() ? appUrl : (baseUrl == null ? "" : baseUrl);
        String link = base.replaceAll("/$", "") + "/convite-admin/" + invite.getToken();
        String listaAreas = String.join(", ", invite.getAdminAreas().stream()
                .map(a -> AdminArea.AREAS_ADMIN_LABEL.getOrDefault(a, a)).toList());
        String texto = String.join("\n",
                "Olá " + invite.getName() + ",", "",
                "Foi convidado a ser Admin do Sistema KIXIMA, com acesso às seguintes áreas:",
                listaAreas, "",
                "Clique no link abaixo para completar o seu cadastro e definir a sua senha:", link, "",
                "Este link é válido por " + ADMIN_INVITE_TTL_DIAS + " dias e só pode ser usado uma vez.", "",
                "Se não esperava este convite, ignore este email — ninguém consegue aceder com ele sem o clicar.", "",
                "Equipa Kixima.");
        emailDispatchService.dispatch(invite.getEmail(), "Convite para acesso administrativo ao Kixima", texto);
    }

    // --- Gestão de Atividades -------------------------------------------

    @Transactional(readOnly = true)
    public Map<String, Object> systemActivities() {
        List<PurchaseOrder> orders = purchaseOrderRepository.findRecentesComEmpresas(PageRequest.of(0, 25));
        List<Company> companies = companyRepository.findTop10ByOrderByCreatedAtDesc();
        List<User> users = userRepository.findTop10ByOrderByCreatedAtDesc();
        List<Payment> payments = paymentRepository.findTop10ByOrderByProcessedAtDesc();
        List<SupportTicket> tickets = supportTicketRepository.findTop10ByOrderByCreatedAtDesc();
        List<SupplierToKiximaPolicy> policies = policyRepository.findTop10ByOrderByCreatedAtDesc();

        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("empresas", companyRepository.count());
        kpis.put("utilizadores", userRepository.count());
        kpis.put("ordens", purchaseOrderRepository.count());
        kpis.put("pagamentos", paymentRepository.count());
        kpis.put("tickets", supportTicketRepository.count());

        List<Map<String, Object>> ev = new ArrayList<>();
        for (PurchaseOrder o : orders) {
            ev.add(evento("Ordem de Compra", "Compras", "PO " + o.getReference(),
                    pretty(o.getStatus()) + " — " + nome(o.getBuyerCompany()) + " → " + nome(o.getSupplierCompany()), o.getUpdatedAt()));
        }
        for (Company c : companies) {
            ev.add(evento("Empresa", "Credenciamento", c.getName(), "Empresa " + pretty(c.getType()) + " — estado " + pretty(c.getStatus()), c.getCreatedAt()));
        }
        for (User u : users) {
            Company empresa = u.getCompanyId() == null ? null : companyRepository.findById(u.getCompanyId()).orElse(null);
            ev.add(evento("Utilizador", "Contas", u.getName(), "Novo " + pretty(u.getRole()) + " — " + (empresa == null ? "—" : empresa.getName()), u.getCreatedAt()));
        }
        for (Payment p : payments) ev.add(evento("Pagamento", "Financeiro", p.getReference(), "Pagamento processado", p.getProcessedAt()));
        for (SupportTicket t : tickets) ev.add(evento("Suporte", "Ajuda", "#" + t.getReference(), t.getSubject(), t.getCreatedAt()));
        for (SupplierToKiximaPolicy pl : policies) {
            Company empresa = companyRepository.findById(pl.getCompanyId()).orElse(null);
            ev.add(evento("Apólice", "Seguros", pl.getPolicyNumber(), pl.getInsurer() + " — " + (empresa == null ? "" : empresa.getName()), pl.getCreatedAt()));
        }
        ev.sort((a, b) -> {
            Instant ia = (Instant) a.get("at");
            Instant ib = (Instant) b.get("at");
            if (ia == null && ib == null) return 0;
            if (ia == null) return 1;
            if (ib == null) return -1;
            return ib.compareTo(ia);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kpis", kpis);
        out.put("items", ev.subList(0, Math.min(60, ev.size())));
        return out;
    }

    private static String nome(Company c) {
        return c == null ? null : c.getName();
    }

    private static Map<String, Object> evento(String type, String module, String title, String detail, Instant at) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("module", module);
        m.put("title", title);
        m.put("detail", detail);
        m.put("at", at);
        return m;
    }
}
