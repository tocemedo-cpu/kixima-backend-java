package ao.kixima.policy;

import ao.kixima.common.error.ForbiddenException;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyRepository;
import ao.kixima.company.CompanyType;
import ao.kixima.company.PolicyStatus;
import ao.kixima.company.SupplierToKiximaPolicy;
import ao.kixima.company.SupplierToKiximaPolicyRepository;
import ao.kixima.notification.NotificationChannel;
import ao.kixima.notification.NotificationService;
import ao.kixima.notification.NotificationType;
import ao.kixima.policy.dto.ClientPolicyDto;
import ao.kixima.policy.dto.CompanyPoliciesDto;
import ao.kixima.policy.dto.PolicyRequest;
import ao.kixima.policy.dto.SupplierPolicyDto;
import ao.kixima.security.PersonaRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Espelha backend/src/services/policyService.js — as duas apólices de
 * seguro do marketplace (secção 4 da especificação):
 * <ul>
 *   <li>Fornecedor→KIXIMA: submetida pelo próprio fornecedor no onboarding
 *   ({@link #submeterApoliceFornecedor}), decidida pelo Admin do Sistema
 *   ({@link #decidirApoliceFornecedor}).</li>
 *   <li>KIXIMA→Cliente: emitida directamente pelo Admin do Sistema após due
 *   diligence ({@link #emitirApoliceCliente}), com aviso automático de
 *   expiração ({@link #enviarAvisosDeExpiracao()} — ver PolicyExpiryJob,
 *   M6).</li>
 * </ul>
 */
@Service
public class PolicyService {

    private static final Logger log = LoggerFactory.getLogger(PolicyService.class);
    private static final DateTimeFormatter DATA = DateTimeFormatter.ISO_LOCAL_DATE;

    private final SupplierToKiximaPolicyRepository supplierPolicyRepository;
    private final KiximaToClientPolicyRepository clientPolicyRepository;
    private final CompanyRepository companyRepository;
    private final NotificationService notificationService;
    private final int alertDays;

    public PolicyService(SupplierToKiximaPolicyRepository supplierPolicyRepository,
                          KiximaToClientPolicyRepository clientPolicyRepository, CompanyRepository companyRepository,
                          NotificationService notificationService,
                          @Value("${kixima.business.policy-expiry-alert-days}") int alertDays) {
        this.supplierPolicyRepository = supplierPolicyRepository;
        this.clientPolicyRepository = clientPolicyRepository;
        this.companyRepository = companyRepository;
        this.notificationService = notificationService;
        this.alertDays = alertDays;
    }

    @Transactional
    public SupplierPolicyDto submeterApoliceFornecedor(String companyId, PolicyRequest body) {
        Company company = companyRepository.findById(companyId).orElseThrow(() -> new NotFoundException("Empresa"));
        if (company.getType() != CompanyType.FORNECEDOR) {
            throw new ForbiddenException("Apenas empresas fornecedoras submetem a apólice Fornecedor→KIXIMA.");
        }

        SupplierToKiximaPolicy policy = new SupplierToKiximaPolicy(UUID.randomUUID().toString(), companyId,
                body.policyNumber(), body.insurer(), body.coverageAmount(), body.currency(), body.validFrom(),
                body.validUntil(), Instant.now());
        supplierPolicyRepository.save(policy);

        apoliceSubmetidaOuAprovada(companyId, "Fornecedor→KIXIMA");
        return SupplierPolicyDto.from(policy);
    }

    @Transactional
    public SupplierPolicyDto decidirApoliceFornecedor(String policyId, boolean aprovar) {
        SupplierToKiximaPolicy policy = supplierPolicyRepository.findById(policyId)
                .orElseThrow(() -> new NotFoundException("Apólice"));
        policy.setStatus(aprovar ? PolicyStatus.APROVADA : PolicyStatus.REJEITADA);
        if (aprovar) {
            apoliceSubmetidaOuAprovada(policy.getCompanyId(), "Fornecedor→KIXIMA");
        }
        return SupplierPolicyDto.from(policy);
    }

    @Transactional
    public ClientPolicyDto emitirApoliceCliente(String companyId, PolicyRequest body, String issuedById) {
        Company company = companyRepository.findById(companyId).orElseThrow(() -> new NotFoundException("Empresa"));

        KiximaToClientPolicy policy = new KiximaToClientPolicy(UUID.randomUUID().toString(), company.getId(),
                body.policyNumber(), body.insurer(), body.coverageAmount(), body.currency(), issuedById,
                body.validFrom(), body.validUntil(), Instant.now());
        clientPolicyRepository.save(policy);

        apoliceSubmetidaOuAprovada(companyId, "KIXIMA→Cliente");
        return ClientPolicyDto.from(policy);
    }

    @Transactional(readOnly = true)
    public CompanyPoliciesDto listarApolicesDaEmpresa(String companyId) {
        List<SupplierPolicyDto> supplier = supplierPolicyRepository.findByCompanyId(companyId).stream()
                .map(SupplierPolicyDto::from).toList();
        List<ClientPolicyDto> client = clientPolicyRepository.findByCompanyId(companyId).stream()
                .map(ClientPolicyDto::from).toList();
        return new CompanyPoliciesDto(supplier, client);
    }

    /**
     * Job periódico (ver ao.kixima.policy.PolicyExpiryJob, M6): avisa Company
     * Admin e Financeiro {@code alertDays} dias antes da apólice KIXIMA→Cliente
     * expirar. Evita reenvio usando {@code expiryAlertSentAt}.
     */
    @Transactional
    public int enviarAvisosDeExpiracao() {
        Instant agora = Instant.now();
        Instant limiar = agora.plus(Duration.ofDays(alertDays));

        List<KiximaToClientPolicy> aExpirar = clientPolicyRepository
                .findByStatusAndValidUntilBetweenAndExpiryAlertSentAtIsNull(PolicyStatus.APROVADA, agora, limiar);

        for (KiximaToClientPolicy policy : aExpirar) {
            apoliceAExpirar(policy.getCompanyId(), "KIXIMA→Cliente", policy.getValidUntil());
            policy.setExpiryAlertSentAt(Instant.now());
        }

        if (!aExpirar.isEmpty()) {
            log.info("Apólices: {} aviso(s) de expiração enviado(s)", aExpirar.size());
        }
        return aExpirar.size();
    }

    private void apoliceSubmetidaOuAprovada(String companyId, String policyLabel) {
        notificationService.notifyUsersByRole(companyId, List.of(PersonaRole.COMPANY_ADMIN, PersonaRole.FINANCEIRO),
                NotificationType.APOLICE_SUBMETIDA_APROVADA, "Apólice atualizada",
                "A apólice " + policyLabel + " foi submetida/aprovada.", NotificationChannel.IN_APP_EMAIL, null, null);
    }

    private void apoliceAExpirar(String companyId, String policyLabel, Instant validUntil) {
        notificationService.notifyUsersByRole(companyId, List.of(PersonaRole.COMPANY_ADMIN, PersonaRole.FINANCEIRO),
                NotificationType.APOLICE_A_EXPIRAR, "Apólice a expirar em breve",
                "A apólice " + policyLabel + " expira em " + DATA.format(validUntil.atZone(java.time.ZoneOffset.UTC))
                        + ". Providencie a renovação.",
                NotificationChannel.IN_APP_EMAIL, null, null);
    }
}
