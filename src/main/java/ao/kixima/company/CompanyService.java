package ao.kixima.company;

import ao.kixima.common.error.BusinessRuleException;
import ao.kixima.common.error.ConflictException;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.common.error.ValidationException;
import ao.kixima.company.dto.CompanyDto;
import ao.kixima.company.dto.CompanyRequests;
import ao.kixima.company.dto.SubscriptionDto;
import ao.kixima.invite.dto.CompanyUserDto;
import ao.kixima.notification.NotificationService;
import ao.kixima.notification.NotificationType;
import ao.kixima.plan.PlanService;
import ao.kixima.policy.KiximaToClientPolicyRepository;
import ao.kixima.policy.dto.ClientPolicyDto;
import ao.kixima.policy.dto.SupplierPolicyDto;
import ao.kixima.security.PersonaRole;
import ao.kixima.storage.StorageService;
import ao.kixima.user.PasswordPolicy;
import ao.kixima.user.User;
import ao.kixima.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Espelha backend/src/services/companyService.js (parte de cadastro/due
 * diligence; os convites e a equipa vivem em {@link ao.kixima.invite.InviteService}).
 */
@Service
public class CompanyService {

    /** Documentos obrigatórios por tipo de empresa (secção 4.1). */
    private static final Map<CompanyType, List<DocumentType>> REQUIRED_DOCS = new EnumMap<>(Map.of(
            CompanyType.CLIENTE, List.of(DocumentType.CERTIDAO_COMERCIAL, DocumentType.ALVARA_COMERCIAL),
            CompanyType.FORNECEDOR, List.of(DocumentType.ALVARA_COMERCIAL, DocumentType.LICENCA_ANPG, DocumentType.CERTIDAO_COMERCIAL)));
    private static final Map<DocumentType, String> DOC_LABELS = new EnumMap<>(Map.of(
            DocumentType.CERTIDAO_COMERCIAL, "Certidão Comercial",
            DocumentType.ALVARA_COMERCIAL, "Alvará Comercial",
            DocumentType.LICENCA_ANPG, "Licença da ANPG"));
    private static final Set<PersonaRole> PERFIS_CRIAVEIS =
            Set.of(PersonaRole.COMPRADOR, PersonaRole.COMPANY_ADMIN, PersonaRole.FORNECEDOR, PersonaRole.FINANCEIRO);

    public record DocumentoEnviado(DocumentType type, MultipartFile file) {
    }

    private final CompanyRepository companyRepository;
    private final CompanyDocumentRepository documentRepository;
    private final SupplierToKiximaPolicyRepository supplierPolicyRepository;
    private final KiximaToClientPolicyRepository clientPolicyRepository;
    private final BudgetLimitRepository budgetLimitRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final PlanService planService;
    private final StorageService storageService;
    private final NotificationService notificationService;

    public CompanyService(CompanyRepository companyRepository, CompanyDocumentRepository documentRepository,
                          SupplierToKiximaPolicyRepository supplierPolicyRepository, KiximaToClientPolicyRepository clientPolicyRepository,
                          BudgetLimitRepository budgetLimitRepository, UserRepository userRepository, PasswordEncoder passwordEncoder,
                          PasswordPolicy passwordPolicy, PlanService planService, StorageService storageService,
                          NotificationService notificationService) {
        this.companyRepository = companyRepository;
        this.documentRepository = documentRepository;
        this.supplierPolicyRepository = supplierPolicyRepository;
        this.clientPolicyRepository = clientPolicyRepository;
        this.budgetLimitRepository = budgetLimitRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.planService = planService;
        this.storageService = storageService;
        this.notificationService = notificationService;
    }

    // --- helpers de coerção (o cadastro chega por multipart, tudo em texto) ---

    private static boolean vazio(String v) {
        return v == null || v.isBlank();
    }

    private static Integer inteiroOuNull(String v) {
        if (vazio(v)) return null;
        try {
            int n = Integer.parseInt(v.trim());
            if (n < 0) throw new ValidationException("employees: tem de ser um inteiro não-negativo.");
            return n;
        } catch (NumberFormatException e) {
            throw new ValidationException("employees: tem de ser um inteiro não-negativo.");
        }
    }

    private static BigDecimal decimalOuNull(String v, String campo) {
        if (vazio(v)) return null;
        try {
            BigDecimal d = new BigDecimal(v.trim());
            if (d.signum() < 0) throw new ValidationException(campo + ": tem de ser um número não-negativo.");
            return d;
        } catch (NumberFormatException e) {
            throw new ValidationException(campo + ": tem de ser um número não-negativo.");
        }
    }

    /** `z.coerce.date()` aceita ISO completo ou só a data. */
    static Instant dataOuNull(String v, String campo) {
        if (vazio(v)) return null;
        String t = v.trim();
        try {
            return Instant.parse(t);
        } catch (DateTimeParseException ignorado) {
            try {
                return LocalDate.parse(t).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException e) {
                throw new ValidationException(campo + ": data inválida.");
            }
        }
    }

    private static byte[] bytesDe(MultipartFile f) {
        try {
            return f.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Falha a ler o ficheiro enviado.", e);
        }
    }

    private static void validarDocumento(MultipartFile f) {
        String tipo = f.getContentType();
        boolean valido = tipo != null && (tipo.matches("^image/(png|jpe?g|webp|gif)$") || tipo.equals("application/pdf"));
        if (!valido) throw new ValidationException("Documento inválido — use PDF ou imagem (PNG/JPG).");
        if (f.getSize() > 10L * 1024 * 1024) throw new ValidationException("O documento é demasiado grande (máximo 10 MB).");
    }

    /**
     * Cadastro público de empresa: cria a empresa (PENDENTE), o primeiro
     * utilizador (Company Admin, com senha — para poder entrar após a
     * aprovação) e guarda os documentos de credenciamento exigidos para o tipo.
     */
    @Transactional
    public CompanyDto registerCompany(CompanyRequests.Register data, List<DocumentoEnviado> uploadedDocs, MultipartFile policyFile) {
        CompanyType type;
        try {
            type = CompanyType.valueOf(data.type);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("type: use CLIENTE ou FORNECEDOR.");
        }
        if (!"true".equalsIgnoreCase(data.termsAccepted)) {
            throw new ValidationException("É necessário aceitar os Termos de Uso e a Política de Privacidade.");
        }
        String erroSenha = passwordPolicy.validar(data.adminPassword, PersonaRole.COMPANY_ADMIN, data.adminEmail);
        if (erroSenha != null) throw new ValidationException(erroSenha);
        if (!vazio(data.plan) && !data.plan.equals("BASICO") && !data.plan.equals("PRO")) {
            throw new ValidationException("plan: use BASICO ou PRO.");
        }
        Integer employees = inteiroOuNull(data.employees);
        BigDecimal annualRevenueUsd = decimalOuNull(data.annualRevenueUsd, "annualRevenueUsd");
        Instant policyValidFrom = dataOuNull(data.policyValidFrom, "policyValidFrom");
        Instant policyValidUntil = dataOuNull(data.policyValidUntil, "policyValidUntil");
        if (data.coverageAmount != null && data.coverageAmount.signum() <= 0) throw new ValidationException("coverageAmount: tem de ser positivo.");

        // 1. Documentos obrigatórios presentes?
        List<DocumentType> required = REQUIRED_DOCS.getOrDefault(type, List.of());
        Set<DocumentType> providedTypes = new java.util.HashSet<>();
        uploadedDocs.forEach(d -> providedTypes.add(d.type()));
        List<String> missing = required.stream().filter(t -> !providedTypes.contains(t)).map(DOC_LABELS::get).toList();
        if (!missing.isEmpty()) {
            throw new BusinessRuleException("Documentos obrigatórios em falta: " + String.join(", ", missing) + ".");
        }

        // 1b. Apólice de seguro Fornecedor→KIXIMA — obrigatória para fornecedoras.
        if (type == CompanyType.FORNECEDOR) {
            if (vazio(data.policyNumber) || vazio(data.insurer) || data.coverageAmount == null || policyValidFrom == null || policyValidUntil == null) {
                throw new BusinessRuleException("A apólice de seguro (Fornecedor→KIXIMA) é obrigatória no cadastro de empresas fornecedoras"
                        + " — indique seguradora, nº, cobertura e validade.");
            }
            if (policyFile == null || policyFile.isEmpty()) {
                throw new BusinessRuleException("Anexe o documento (PDF/imagem) da apólice de seguro.");
            }
        }

        // 2. Unicidade (falhar cedo, antes de guardar ficheiros).
        if (companyRepository.existsByTaxId(data.taxId)) throw new ConflictException("Já existe uma empresa registada com este NIF.");
        String adminEmail = data.adminEmail.trim().toLowerCase();
        if (userRepository.findByEmail(adminEmail).isPresent()) throw new ConflictException("Já existe uma conta com este email.");

        // 3. Guardar os ficheiros dos documentos exigidos.
        record Guardado(DocumentType type, String fileUrl, String originalName) {
        }
        List<Guardado> docRecords = new ArrayList<>();
        for (DocumentoEnviado d : uploadedDocs) {
            if (!required.contains(d.type())) continue;
            validarDocumento(d.file());
            String fileUrl = storageService.saveFile(bytesDe(d.file()), d.file().getOriginalFilename(), d.file().getContentType(),
                    data.taxId + "-" + d.type().name(), "documents");
            docRecords.add(new Guardado(d.type(), fileUrl, d.file().getOriginalFilename()));
        }
        String policyDocumentUrl = null;
        if (type == CompanyType.FORNECEDOR) {
            validarDocumento(policyFile);
            policyDocumentUrl = storageService.saveFile(bytesDe(policyFile), policyFile.getOriginalFilename(), policyFile.getContentType(),
                    data.taxId + "-APOLICE", "documents");
        }

        // 4. Criar empresa + admin + documentos (+ apólice, se fornecedora) — mesma transação.
        Instant agora = Instant.now();
        CompanySize size = planService.classify(employees, annualRevenueUsd);
        CompanyPlan minPlan = planService.requiredPlan(size);
        CompanyPlan plan = "PRO".equals(data.plan) || minPlan == CompanyPlan.PRO ? CompanyPlan.PRO : CompanyPlan.BASICO;

        Company company = new Company(UUID.randomUUID().toString(), data.name, data.taxId, type, data.contactEmail,
                vazio(data.contactPhone) ? null : data.contactPhone, null, employees, agora);
        company.setAddress(vazio(data.address) ? null : data.address);
        company.setStatus(CompanyStatus.PENDENTE);
        company.setTermsAcceptedAt(agora);
        company.setAnnualRevenueUsd(annualRevenueUsd);
        company.setSize(size);
        company.setPlan(plan);
        company.setSearchRank(planService.rankDoPlano(plan));
        companyRepository.save(company);

        User admin = new User(UUID.randomUUID().toString(), data.adminName, adminEmail, passwordEncoder.encode(data.adminPassword),
                PersonaRole.COMPANY_ADMIN, company.getId(), true, agora, agora);
        userRepository.save(admin);

        for (Guardado dr : docRecords) {
            documentRepository.save(new CompanyDocument(UUID.randomUUID().toString(), company.getId(), dr.type(), dr.fileUrl(),
                    dr.originalName(), agora));
        }
        if (type == CompanyType.FORNECEDOR) {
            SupplierToKiximaPolicy policy = new SupplierToKiximaPolicy(UUID.randomUUID().toString(), company.getId(), data.policyNumber,
                    data.insurer, data.coverageAmount, vazio(data.policyCurrency) ? "AOA" : data.policyCurrency, policyValidFrom,
                    policyValidUntil, agora);
            policy.setDocumentUrl(policyDocumentUrl);
            policy.setStatus(PolicyStatus.SUBMETIDA);
            supplierPolicyRepository.save(policy);
        }
        return CompanyDto.de(company);
    }

    @Transactional(readOnly = true)
    public List<CompanyDto> listCompanies(String statusBruto, String typeBruto, String comSubscricao) {
        CompanyStatus status = vazio(statusBruto) ? null : enumOu(CompanyStatus.class, statusBruto, "status");
        CompanyType type = vazio(typeBruto) ? null : enumOu(CompanyType.class, typeBruto, "type");
        List<Company> companies;
        if (status != null && type != null) companies = companyRepository.findByStatusAndTypeOrderByCreatedAtDesc(status, type);
        else if (status != null) companies = companyRepository.findByStatusOrderByCreatedAtDesc(status);
        else if (type != null) companies = companyRepository.findByTypeOrderByCreatedAtDesc(type);
        else companies = companyRepository.findAllByOrderByCreatedAtDesc();

        // A subscrição só vem quando é pedida — quem só quer a lista não paga a contagem.
        if (!"true".equals(comSubscricao)) return companies.stream().map(CompanyDto::de).toList();
        Map<String, SubscriptionDto> subs = subscriptionsFor(companies);
        // `subscricao: subs.get(c.id) || null` — a chave sai sempre nesta listagem.
        return companies.stream().map(c -> CompanyDto.de(c, null, null, null, null, Optional.ofNullable(subs.get(c.getId())))).toList();
    }

    private static <E extends Enum<E>> E enumOu(Class<E> tipo, String v, String campo) {
        try {
            return Enum.valueOf(tipo, v);
        } catch (IllegalArgumentException e) {
            throw new ValidationException(campo + ": valor inválido.");
        }
    }

    /** Ficha completa: apólices, limite de orçamento e documentos (getCompany do Node). */
    @Transactional(readOnly = true)
    public CompanyDto getCompany(String id) {
        Company c = companyRepository.findById(id).orElseThrow(() -> new NotFoundException("Empresa"));
        List<SupplierPolicyDto> supplierPolicies = supplierPolicyRepository.findByCompanyId(id).stream().map(SupplierPolicyDto::from).toList();
        List<ClientPolicyDto> clientPolicies = clientPolicyRepository.findByCompanyId(id).stream().map(ClientPolicyDto::from).toList();
        // Relação 1:1 no `include`: `budgetLimit: null` quando não há — a chave sai na mesma.
        Optional<CompanyDto.BudgetLimitDto> budgetLimit = budgetLimitRepository.findByCompanyId(id).map(CompanyDto.BudgetLimitDto::de);
        List<CompanyDto.CompanyDocumentDto> documents = documentRepository.findByCompanyIdOrderByTypeAsc(id).stream()
                .map(CompanyDto.CompanyDocumentDto::de).toList();
        return CompanyDto.de(c, supplierPolicies, clientPolicies, budgetLimit, documents, null);
    }

    /**
     * Aprovação de cadastro pelo Admin do Sistema. Fornecedores só podem ser
     * aprovados se já tiverem submetido a apólice Fornecedor→KIXIMA.
     */
    @Transactional
    public CompanyDto decideCompanyStatus(String companyId, boolean approve) {
        Company company = companyRepository.findById(companyId).orElseThrow(() -> new NotFoundException("Empresa"));
        if (approve && company.getType() == CompanyType.FORNECEDOR) {
            boolean hasSubmittedPolicy = supplierPolicyRepository.findByCompanyId(companyId).stream()
                    .anyMatch(p -> p.getStatus() != PolicyStatus.REJEITADA);
            if (!hasSubmittedPolicy) {
                throw new BusinessRuleException("Fornecedor não pode ser aprovado sem apólice Fornecedor→KIXIMA submetida.");
            }
        }
        company.decidir(approve, Instant.now());
        boolean aprovada = company.getStatus() == CompanyStatus.APROVADA;
        notificationService.notifyCompanyContact(company.getId(),
                aprovada ? NotificationType.CADASTRO_EMPRESA_APROVADO : NotificationType.CADASTRO_EMPRESA_REJEITADO,
                aprovada ? "Cadastro aprovado" : "Cadastro rejeitado",
                aprovada ? "O cadastro da empresa " + company.getName() + " foi aprovado. Já pode transacionar na KIXIMA."
                        : "O cadastro da empresa " + company.getName() + " foi rejeitado.");
        return CompanyDto.de(company);
    }

    @Transactional
    public CompanyDto.BudgetLimitDto setBudgetLimit(String companyId, BigDecimal periodMonthly, String currency) {
        if (!companyRepository.existsById(companyId)) throw new NotFoundException("Empresa");
        BudgetLimit limit = budgetLimitRepository.findByCompanyId(companyId).orElse(null);
        if (limit == null) {
            limit = budgetLimitRepository.save(new BudgetLimit(UUID.randomUUID().toString(), companyId, periodMonthly, currency));
        } else {
            limit.atualizar(periodMonthly, currency);
        }
        return CompanyDto.BudgetLimitDto.de(limit);
    }

    public record BankDetailsDto(String id, String name, String bankName, String iban, String swift) {
        static BankDetailsDto de(Company c) {
            return new BankDetailsDto(c.getId(), c.getName(), c.getBankName(), c.getIban(), c.getSwift());
        }
    }

    @Transactional(readOnly = true)
    public BankDetailsDto getBankDetails(String companyId) {
        return BankDetailsDto.de(companyRepository.findById(companyId).orElseThrow(() -> new NotFoundException("Empresa")));
    }

    @Transactional
    public BankDetailsDto updateBankDetails(String companyId, CompanyRequests.BankDetails body) {
        Company c = companyRepository.findById(companyId).orElseThrow(() -> new NotFoundException("Empresa"));
        c.setDadosBancarios(
                vazio(body.bankName()) ? null : body.bankName().trim(),
                vazio(body.iban()) ? null : body.iban().replaceAll("\\s+", "").toUpperCase(),
                vazio(body.swift()) ? null : body.swift().replaceAll("\\s+", "").toUpperCase());
        return BankDetailsDto.de(c);
    }

    public record SerieFiscalDto(String id, String name, String serieFiscal) {
    }

    /** `null`/vazio desliga a série de volta — as faturas seguintes saem sem série nem hash. */
    @Transactional
    public SerieFiscalDto setSerieFiscal(String companyId, String serieFiscal) {
        Company c = companyRepository.findById(companyId).orElseThrow(() -> new NotFoundException("Empresa"));
        c.setSerieFiscal(vazio(serieFiscal) ? null : serieFiscal.trim());
        return new SerieFiscalDto(c.getId(), c.getName(), c.getSerieFiscal());
    }

    public record DataAdesaoDto(String id, String name, Instant dataAdesaoFacturacaoElectronica) {
    }

    @Transactional
    public DataAdesaoDto setDataAdesaoFacturacaoElectronica(String companyId, Instant dataAdesao) {
        Company c = companyRepository.findById(companyId).orElseThrow(() -> new NotFoundException("Empresa"));
        c.setDataAdesaoFacturacaoElectronica(dataAdesao);
        return new DataAdesaoDto(c.getId(), c.getName(), c.getDataAdesaoFacturacaoElectronica());
    }

    public record PlanDto(String id, String name, String size, String plan, int searchRank, BigDecimal seatPriceUsd, Integer employees,
                          BigDecimal annualRevenueUsd, String planNotes) {
    }

    /** Dimensão e plano — rejeita descer o plano abaixo do exigido pela dimensão (GRANDE exige PRO). */
    @Transactional
    public PlanDto updatePlan(String companyId, CompanyRequests.Plan data) {
        Company c = companyRepository.findById(companyId).orElseThrow(() -> new NotFoundException("Empresa"));
        Integer employees = data.employees() != null ? data.employees() : c.getEmployees();
        BigDecimal annualRevenueUsd = data.annualRevenueUsd() != null ? data.annualRevenueUsd() : c.getAnnualRevenueUsd();
        CompanySize size = vazio(data.size()) ? planService.classify(employees, annualRevenueUsd) : enumOu(CompanySize.class, data.size(), "size");
        CompanyPlan plan = vazio(data.plan()) ? c.getPlan() : enumOu(CompanyPlan.class, data.plan(), "plan");
        if (!vazio(data.plan()) && plan != CompanyPlan.BASICO && plan != CompanyPlan.PRO) {
            throw new ValidationException("plan: use BASICO ou PRO.");
        }
        if (!planService.planAllowed(size, plan)) {
            throw new BusinessRuleException("Empresas de grande dimensão têm de subscrever o plano PRO.");
        }
        if (data.seatPriceUsd() != null && data.seatPriceUsd().compareTo(planService.seatPriceCapUsd()) > 0) {
            throw new BusinessRuleException("O preço por utilizador não pode exceder " + planService.seatPriceCapUsd().stripTrailingZeros().toPlainString() + " USD/mês.");
        }
        c.setSize(size);
        c.setPlan(plan);
        // Derivado do plano, e escrito aqui: senão uma empresa que subisse para Pro continuava no fundo da pesquisa.
        c.setSearchRank(planService.rankDoPlano(plan));
        c.setEmployees(employees);
        c.setAnnualRevenueUsd(annualRevenueUsd);
        if (data.seatPriceUsd() != null) c.setSeatPriceUsd(data.seatPriceUsd());
        if (data.planNotes() != null) c.setPlanNotes(data.planNotes());
        return new PlanDto(c.getId(), c.getName(), c.getSize().name(), c.getPlan().name(), c.getSearchRank(), c.getSeatPriceUsd(),
                c.getEmployees(), c.getAnnualRevenueUsd(), c.getPlanNotes());
    }

    /**
     * Resumo de subscrição de VÁRIAS empresas em UMA consulta de contagem — não
     * uma por empresa (KX2-04). Uma empresa sem utilizadores ativos conta ZERO.
     */
    @Transactional(readOnly = true)
    public Map<String, SubscriptionDto> subscriptionsFor(List<Company> companies) {
        Map<String, SubscriptionDto> saida = new LinkedHashMap<>();
        if (companies.isEmpty()) return saida;
        Map<String, Integer> porEmpresa = new HashMap<>();
        for (Object[] linha : userRepository.contagemAtivosPorEmpresa(companies.stream().map(Company::getId).toList())) {
            porEmpresa.put((String) linha[0], ((Number) linha[1]).intValue());
        }
        for (Company c : companies) {
            int activeUsers = porEmpresa.getOrDefault(c.getId(), 0);
            saida.put(c.getId(), new SubscriptionDto(SubscriptionDto.CompanyRef.de(c), activeUsers,
                    planService.monthlyAccessCost(activeUsers, c.getSeatPriceUsd()), planService.requiredPlan(c.getSize()).name(),
                    planService.features(c.getPlan()), planService.seatPriceCapUsd()));
        }
        return saida;
    }

    /** Resumo de UMA empresa, construído sobre a versão em lote de propósito. */
    @Transactional(readOnly = true)
    public SubscriptionDto subscriptionFor(String companyId) {
        Company c = companyRepository.findById(companyId).orElseThrow(() -> new NotFoundException("Empresa"));
        return subscriptionsFor(List.of(c)).get(companyId);
    }

    /** authService.createUser + a guarda de perfis do createUserSchema (nunca ADMIN_SISTEMA por aqui). */
    @Transactional
    public CompanyUserDto createUser(CompanyRequests.CreateUser body, String companyId) {
        PersonaRole role = enumOu(PersonaRole.class, body.role(), "role");
        if (!PERFIS_CRIAVEIS.contains(role)) throw new ValidationException("role: perfil inválido para criação direta.");
        String email = body.email().trim().toLowerCase();
        String erroSenha = passwordPolicy.validar(body.password(), role, email);
        if (erroSenha != null) throw new ValidationException(erroSenha);
        if (userRepository.findByEmail(email).isPresent()) throw new ConflictException("Já existe um utilizador com este email.");
        Instant agora = Instant.now();
        User user = new User(UUID.randomUUID().toString(), body.name(), email, passwordEncoder.encode(body.password()), role, companyId,
                true, null, agora);
        user.setApprovalCap(body.approvalCap());
        userRepository.save(user);
        return new CompanyUserDto(user.getId(), user.getName(), user.getEmail(), user.getRole().name(), user.isActive(), user.getCreatedAt());
    }
}
