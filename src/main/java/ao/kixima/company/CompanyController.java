package ao.kixima.company;

import ao.kixima.audit.Actor;
import ao.kixima.audit.AuditService;
import ao.kixima.common.error.ForbiddenException;
import ao.kixima.company.dto.CompanyDto;
import ao.kixima.company.dto.CompanyRequests;
import ao.kixima.company.dto.SubscriptionDto;
import ao.kixima.invite.dto.CompanyUserDto;
import ao.kixima.security.CurrentUser;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.PersonaRole;
import ao.kixima.security.RequirePermission;
import ao.kixima.security.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ao.kixima.security.AdminArea.CADASTRO;
import static ao.kixima.security.AdminArea.FATURACAO;
import static ao.kixima.security.AdminArea.FINANCEIRO;
import static ao.kixima.security.PersonaRole.ADMIN_SISTEMA;
import static ao.kixima.security.PersonaRole.COMPANY_ADMIN;
import static ao.kixima.security.PersonaRole.FORNECEDOR;
import static org.springframework.http.HttpStatus.CREATED;

/**
 * Espelha companyRoutes.js/companyController.js — cadastro público, due
 * diligence e configuração da empresa. Os convites/equipa estão em
 * {@link ao.kixima.invite.InviteController}, o ERP em
 * {@link ao.kixima.erp.ErpConfigController} e o extrato de taxas em
 * {@link CompanyPlatformFeeController} (mesmo prefixo).
 */
@RestController
@RequestMapping("/api/companies")
public class CompanyController {

    private final CompanyService companyService;
    private final AuditService auditService;

    public CompanyController(CompanyService companyService, AuditService auditService) {
        this.companyService = companyService;
        this.auditService = auditService;
    }

    /** No trilho de auditoria o IBAN nunca fica em claro — só os últimos 4 dígitos. */
    static String maskIban(String iban) {
        String s = iban == null ? "" : iban.replaceAll("\\s+", "");
        if (s.isEmpty()) return null;
        return s.length() <= 4 ? s : "••••" + s.substring(s.length() - 4);
    }

    /** `assertOwnCompany`: o Admin do Sistema vê qualquer empresa; os restantes só a própria. */
    private static void assertOwnCompany(CurrentUser user, String companyId) {
        if (user.role() != PersonaRole.ADMIN_SISTEMA && !companyId.equals(user.companyId())) {
            throw new ForbiddenException("Não pode aceder aos dados de outra empresa.");
        }
    }

    private Actor actor(HttpServletRequest req) {
        return auditService.actorFrom(CurrentUserHolder.get(), req);
    }

    /** Cadastro público (onboarding) — multipart: dados + documentos (um por tipo). */
    @PostMapping(value = "/register", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE, MediaType.APPLICATION_FORM_URLENCODED_VALUE})
    @ResponseStatus(CREATED)
    public CompanyDto register(@Valid @ModelAttribute CompanyRequests.Register body,
                               @RequestParam(value = "CERTIDAO_COMERCIAL", required = false) MultipartFile certidao,
                               @RequestParam(value = "ALVARA_COMERCIAL", required = false) MultipartFile alvara,
                               @RequestParam(value = "LICENCA_ANPG", required = false) MultipartFile licenca,
                               @RequestParam(value = "APOLICE_SEGURO", required = false) MultipartFile apolice) {
        List<CompanyService.DocumentoEnviado> docs = new ArrayList<>();
        if (certidao != null && !certidao.isEmpty()) docs.add(new CompanyService.DocumentoEnviado(DocumentType.CERTIDAO_COMERCIAL, certidao));
        if (alvara != null && !alvara.isEmpty()) docs.add(new CompanyService.DocumentoEnviado(DocumentType.ALVARA_COMERCIAL, alvara));
        if (licenca != null && !licenca.isEmpty()) docs.add(new CompanyService.DocumentoEnviado(DocumentType.LICENCA_ANPG, licenca));
        return companyService.registerCompany(body, docs, apolice == null || apolice.isEmpty() ? null : apolice);
    }

    /** Criação direta de utilizador (Company Admin própria empresa ou Admin do Sistema). */
    @PostMapping("/users")
    @ResponseStatus(CREATED)
    @RequireRole({COMPANY_ADMIN, ADMIN_SISTEMA})
    @RequirePermission(CADASTRO)
    public CompanyUserDto createUser(@Valid @RequestBody CompanyRequests.CreateUser body, HttpServletRequest req) {
        CurrentUser user = CurrentUserHolder.get();
        String companyId = user.role() == PersonaRole.ADMIN_SISTEMA ? body.companyId() : user.companyId();
        CompanyUserDto criado = companyService.createUser(body, companyId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("perfil", criado.role());
        detail.put("empresa", companyId);
        detail.putAll(auditService.contextoFrom(req));
        auditService.recordSafe(new AuditService.Entry(actor(req), "UTILIZADOR_CRIADO", "User", criado.id(), criado.email(), detail));
        return criado;
    }

    /** Admin do Sistema KIXIMA: due diligence. */
    @GetMapping
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(CADASTRO)
    public List<CompanyDto> list(@RequestParam(required = false) String status, @RequestParam(required = false) String type,
                                 @RequestParam(required = false) String comSubscricao) {
        return companyService.listCompanies(status, type, comSubscricao);
    }

    @PutMapping("/{id}/plan")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(CADASTRO)
    public CompanyService.PlanDto setPlan(@PathVariable String id, @Valid @RequestBody CompanyRequests.Plan body, HttpServletRequest req) {
        CompanyService.PlanDto result = companyService.updatePlan(id, body);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("dimensao", result.size());
        detail.put("plano", result.plan());
        detail.put("precoPorUtilizador", result.seatPriceUsd().toPlainString());
        auditService.recordSafe(new AuditService.Entry(actor(req), "PLANO_ALTERADO", "Company", result.id(), result.name(), detail));
        return result;
    }

    /** Série de faturação certificada — só depois de a AGT a ter formalmente atribuído a esta empresa. */
    @PutMapping("/{id}/serie-fiscal")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(FATURACAO)
    public CompanyService.SerieFiscalDto setSerieFiscal(@PathVariable String id, @Valid @RequestBody CompanyRequests.SerieFiscal body,
                                                        HttpServletRequest req) {
        CompanyService.SerieFiscalDto result = companyService.setSerieFiscal(id, body.serieFiscal());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("serieFiscal", result.serieFiscal());
        auditService.recordSafe(new AuditService.Entry(actor(req), "SERIE_FISCAL_ALTERADA", "Company", result.id(), result.name(), detail));
        return result;
    }

    @PutMapping("/{id}/data-adesao")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(FATURACAO)
    public CompanyService.DataAdesaoDto setDataAdesao(@PathVariable String id, @RequestBody CompanyRequests.DataAdesao body,
                                                      HttpServletRequest req) {
        CompanyService.DataAdesaoDto result = companyService.setDataAdesaoFacturacaoElectronica(id, body == null ? null : body.dataAdesao());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("dataAdesaoFacturacaoElectronica", result.dataAdesaoFacturacaoElectronica() == null ? null
                : result.dataAdesaoFacturacaoElectronica().toString());
        auditService.recordSafe(new AuditService.Entry(actor(req), "DATA_ADESAO_FACTURACAO_ALTERADA", "Company", result.id(), result.name(), detail));
        return result;
    }

    @GetMapping("/{id}/subscription")
    @RequireRole({COMPANY_ADMIN, FORNECEDOR, PersonaRole.FINANCEIRO, ADMIN_SISTEMA})
    @RequirePermission(FINANCEIRO)
    public SubscriptionDto getSubscription(@PathVariable String id) {
        assertOwnCompany(CurrentUserHolder.get(), id);
        return companyService.subscriptionFor(id);
    }

    @GetMapping("/{id}/bank-details")
    @RequireRole({FORNECEDOR, COMPANY_ADMIN, PersonaRole.FINANCEIRO, ADMIN_SISTEMA})
    @RequirePermission(FINANCEIRO)
    public CompanyService.BankDetailsDto getBankDetails(@PathVariable String id) {
        assertOwnCompany(CurrentUserHolder.get(), id);
        return companyService.getBankDetails(id);
    }

    @PutMapping("/{id}/bank-details")
    @RequireRole({FORNECEDOR, COMPANY_ADMIN, ADMIN_SISTEMA})
    @RequirePermission(FINANCEIRO)
    public CompanyService.BankDetailsDto setBankDetails(@PathVariable String id, @RequestBody CompanyRequests.BankDetails body,
                                                        HttpServletRequest req) {
        assertOwnCompany(CurrentUserHolder.get(), id);
        CompanyService.BankDetailsDto result = companyService.updateBankDetails(id, body);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("banco", result.bankName());
        detail.put("iban", maskIban(result.iban()));
        detail.put("swift", result.swift());
        auditService.recordSafe(new AuditService.Entry(actor(req), "DADOS_BANCARIOS_ALTERADOS", "Company", result.id(), result.name(), detail));
        return result;
    }

    /** Ficha completa da empresa — só o Company Admin (a sua) e o Admin do Sistema (qualquer uma). */
    @GetMapping("/{id}")
    @RequireRole({COMPANY_ADMIN, ADMIN_SISTEMA})
    @RequirePermission(CADASTRO)
    public CompanyDto getOne(@PathVariable String id) {
        assertOwnCompany(CurrentUserHolder.get(), id);
        return companyService.getCompany(id);
    }

    @PatchMapping("/{id}/decision")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(CADASTRO)
    public CompanyDto decide(@PathVariable String id, @Valid @RequestBody CompanyRequests.Decide body, HttpServletRequest req) {
        CompanyDto company = companyService.decideCompanyStatus(id, body.approve());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("decisao", body.approve() ? "APROVADA" : "REJEITADA");
        detail.put("motivo", body.rejectionReason());
        auditService.recordSafe(new AuditService.Entry(actor(req), "EMPRESA_DECIDIDA", "Company", company.id(), company.name(), detail));
        return company;
    }

    @PutMapping("/{id}/budget-limit")
    @RequireRole({COMPANY_ADMIN, ADMIN_SISTEMA})
    @RequirePermission(CADASTRO)
    public CompanyDto.BudgetLimitDto setBudgetLimit(@PathVariable String id, @Valid @RequestBody CompanyRequests.BudgetLimitRequest body) {
        assertOwnCompany(CurrentUserHolder.get(), id);
        return companyService.setBudgetLimit(id, body.periodMonthly(), body.moedaOuAoa());
    }
}
