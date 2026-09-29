package ao.kixima.supplierdev;

import ao.kixima.common.error.BusinessRuleException;
import ao.kixima.common.error.ConflictException;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.common.error.ValidationException;
import ao.kixima.common.reference.ReferenceCounterService;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyRepository;
import ao.kixima.company.CompanyType;
import ao.kixima.company.SupplierToKiximaPolicy;
import ao.kixima.company.SupplierToKiximaPolicyRepository;
import ao.kixima.invite.InviteService;
import ao.kixima.notification.NotificationService;
import ao.kixima.plan.PlanService;
import ao.kixima.security.CurrentUser;
import ao.kixima.supplierdev.dto.ApproveSupplierDevRequest;
import ao.kixima.supplierdev.dto.SupplierDevAccessFeeDto;
import ao.kixima.supplierdev.dto.SupplierDevCreatedDto;
import ao.kixima.supplierdev.dto.SupplierDevKpisDto;
import ao.kixima.supplierdev.dto.SupplierDevListResponse;
import ao.kixima.supplierdev.dto.SupplierDevPublicDto;
import ao.kixima.supplierdev.dto.SupplierDevRequestDto;
import ao.kixima.supplierdev.dto.UpdateSupplierDevRequest;
import ao.kixima.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Espelha backend/src/services/supplierDevService.js — programa Supplier
 * Development (emancipação burocrática + parcerias internacionais). A
 * candidatura é PÚBLICA (pode vir de empresa ainda não registada); o
 * Admin do Sistema trata e acompanha cada caso.
 */
@Service
public class SupplierDevService {

    private static final Logger log = LoggerFactory.getLogger(SupplierDevService.class);
    private static final int MAX_LIMIT = 100;

    private final SupplierDevRequestRepository requestRepository;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final SupplierToKiximaPolicyRepository policyRepository;
    private final ReferenceCounterService referenceCounterService;
    private final NotificationService notificationService;
    private final PlanService planService;
    private final InviteService inviteService;

    public SupplierDevService(SupplierDevRequestRepository requestRepository, CompanyRepository companyRepository,
                               UserRepository userRepository, SupplierToKiximaPolicyRepository policyRepository,
                               ReferenceCounterService referenceCounterService, NotificationService notificationService,
                               PlanService planService, InviteService inviteService) {
        this.requestRepository = requestRepository;
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
        this.policyRepository = policyRepository;
        this.referenceCounterService = referenceCounterService;
        this.notificationService = notificationService;
        this.planService = planService;
        this.inviteService = inviteService;
    }

    /** `companyId` só vem preenchido quando o pedido é feito por alguém já autenticado. */
    @Transactional
    public SupplierDevCreatedDto criar(String companyId, String companyName, String taxId, String contactName,
                                        String contactEmail, String contactPhone, String province, String sector,
                                        Integer employees, SupplierDevTrack track, String needs) {
        String reference = referenceCounterService.nextReference("SD", "supplierDevRequest");
        PlanService.SupplierDevAccessFee fee = planService.supplierDevAccessFee();

        SupplierDevRequest request = new SupplierDevRequest(UUID.randomUUID().toString(), reference, companyId,
                companyName, taxId, contactName, contactEmail, contactPhone, province, sector, employees, track,
                needs, fee.amountUsd(), Instant.now());
        requestRepository.save(request);

        try {
            notificationService.supplierDevRecebida(request);
        } catch (Exception e) {
            log.error("Supplier Development: falha ao notificar a equipa (reference={}): {}", reference, e.getMessage());
        }

        return new SupplierDevCreatedDto(request.getReference(), request.getStatus().name(),
                new SupplierDevAccessFeeDto(fee.amountUsd(), fee.currency(), true, request.getFeeStatus().name(), true));
    }

    @Transactional(readOnly = true)
    public SupplierDevListResponse listar(Integer page, Integer limit, SupplierDevStatus status, SupplierDevTrack track, String q) {
        int take = Math.min(Math.max(1, limit == null ? 25 : limit), MAX_LIMIT);
        int current = Math.max(1, page == null ? 1 : page);

        Page<SupplierDevRequest> pagina = requestRepository.findAll(
                SupplierDevRequestSpecifications.comFiltros(status, track, q),
                PageRequest.of(current - 1, take, Sort.by(Sort.Direction.DESC, "createdAt")));

        Map<SupplierDevStatus, Long> contagens = new java.util.EnumMap<>(SupplierDevStatus.class);
        for (Object[] linha : requestRepository.contagemPorEstado()) {
            contagens.put((SupplierDevStatus) linha[0], (Long) linha[1]);
        }
        long taxasPendentes = requestRepository.countByFeeStatus(PlatformFeeStatus.PENDENTE);
        BigDecimal taxasPendentesUsd = requestRepository.somaTaxaAcessoPorEstado(PlatformFeeStatus.PENDENTE);

        SupplierDevKpisDto kpis = new SupplierDevKpisDto(
                requestRepository.count(),
                contagens.getOrDefault(SupplierDevStatus.RECEBIDA, 0L),
                contagens.getOrDefault(SupplierDevStatus.EM_ANALISE, 0L),
                contagens.getOrDefault(SupplierDevStatus.EM_ACOMPANHAMENTO, 0L),
                contagens.getOrDefault(SupplierDevStatus.CONCLUIDA, 0L),
                taxasPendentes, taxasPendentesUsd == null ? BigDecimal.ZERO : taxasPendentesUsd);

        return new SupplierDevListResponse(pagina.getContent().stream().map(this::toDto).toList(),
                pagina.getTotalElements(), current, Math.max(1, pagina.getTotalPages()), kpis);
    }

    /** Admin atualiza o estado, as notas, a receção da taxa de acesso e o orçamento do restante do programa. */
    @Transactional
    public SupplierDevRequestDto atualizar(String id, UpdateSupplierDevRequest body, CurrentUser user) {
        SupplierDevRequest request = requestRepository.findById(id).orElseThrow(() -> new NotFoundException("Candidatura"));
        if (body.status() != null) request.setStatus(statusValido(body.status()));
        if (body.adminNotes() != null) request.setAdminNotes(body.adminNotes());
        if (body.feeStatus() != null) {
            PlatformFeeStatus feeStatus = feeStatusValido(body.feeStatus());
            request.setFeeStatus(feeStatus);
            request.setFeePaidAt(feeStatus == PlatformFeeStatus.COBRADO
                    ? (request.getFeePaidAt() == null ? Instant.now() : request.getFeePaidAt()) : null);
        }
        if (body.programFeeUsd() != null) {
            request.setProgramFeeUsd(body.programFeeUsd());
            request.setCustomPricing(false);
        }
        request.setHandledById(user != null ? user.id() : request.getHandledById());
        request.setHandledAt(Instant.now());
        return toDto(request);
    }

    /**
     * Aprova uma candidatura SEM empresa associada: cria a Company
     * (PENDENTE, FORNECEDOR), a apólice Fornecedor→KIXIMA e convita o
     * contacto a definir a senha do primeiro Company Admin. Uma
     * candidatura que já tinha empresa só fecha o estado.
     */
    @Transactional
    public SupplierDevRequestDto aprovar(String id, ApproveSupplierDevRequest body, CurrentUser user, String baseUrl) {
        SupplierDevRequest request = requestRepository.findById(id).orElseThrow(() -> new NotFoundException("Candidatura"));
        if (request.getStatus() == SupplierDevStatus.CONCLUIDA) throw new BusinessRuleException("Esta candidatura já foi concluída.");
        if (request.getStatus() == SupplierDevStatus.REJEITADA) throw new BusinessRuleException("Esta candidatura foi rejeitada.");

        if (request.getCompanyId() != null) {
            request.setStatus(SupplierDevStatus.CONCLUIDA);
            request.setHandledById(user != null ? user.id() : request.getHandledById());
            request.setHandledAt(Instant.now());
            return toDto(request);
        }

        String finalTaxId = body.taxId() != null && !body.taxId().isBlank() ? body.taxId().trim()
                : request.getTaxId() != null ? request.getTaxId().trim() : "";
        if (finalTaxId.isBlank()) throw new ValidationException("Indique o NIF da empresa para criar a conta.");
        if (companyRepository.existsByTaxId(finalTaxId)) {
            throw new ConflictException("Já existe uma empresa registada com este NIF.");
        }
        String contactEmail = request.getContactEmail().trim().toLowerCase();
        if (userRepository.findByEmail(contactEmail).isPresent()) {
            throw new ConflictException("Já existe uma conta com este email de contacto.");
        }
        if (body.policy() == null) throw new ValidationException("Indique os dados da apólice Fornecedor→KIXIMA.");

        Company company = new Company(UUID.randomUUID().toString(), request.getCompanyName(), finalTaxId,
                CompanyType.FORNECEDOR, request.getContactEmail(), request.getContactPhone(), request.getProvince(),
                request.getEmployees(), Instant.now());
        companyRepository.save(company);

        ApproveSupplierDevRequest.PolicyInput p = body.policy();
        SupplierToKiximaPolicy policy = new SupplierToKiximaPolicy(UUID.randomUUID().toString(), company.getId(),
                p.policyNumber(), p.insurer(), p.coverageAmount(), p.currency(), p.validFrom(), p.validUntil(), Instant.now());
        policyRepository.save(policy);

        request.setStatus(SupplierDevStatus.CONCLUIDA);
        request.setCompanyId(company.getId());
        request.setHandledById(user != null ? user.id() : null);
        request.setHandledAt(Instant.now());

        inviteService.criarConviteDeFundacao(company, request.getContactName(), contactEmail,
                user != null ? user.id() : null, baseUrl);

        return toDto(request);
    }

    @Transactional(readOnly = true)
    public SupplierDevPublicDto acompanharPorReferencia(String reference) {
        SupplierDevRequest r = requestRepository.findByReference(reference).orElseThrow(() -> new NotFoundException("Candidatura"));
        return new SupplierDevPublicDto(r.getId(), r.getReference(), r.getCompanyName(), r.getTrack().name(),
                r.getStatus().name(), r.getCreatedAt(), r.getAccessFeeUsd(), r.getFeeStatus().name(), r.getFeePaidAt(),
                r.getProgramFeeUsd(), r.isCustomPricing());
    }

    private SupplierDevStatus statusValido(String status) {
        try {
            SupplierDevStatus s = SupplierDevStatus.valueOf(status);
            // CONCLUIDA fica de fora de propósito — concluir exige o fluxo de aprovação (empresa + apólice + convite).
            if (s == SupplierDevStatus.CONCLUIDA) throw new IllegalArgumentException();
            return s;
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Estado inválido para actualização direta — use o endpoint de aprovação para concluir.");
        }
    }

    private PlatformFeeStatus feeStatusValido(String feeStatus) {
        try {
            return PlatformFeeStatus.valueOf(feeStatus);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Estado de taxa inválido.");
        }
    }

    private SupplierDevRequestDto toDto(SupplierDevRequest r) {
        return new SupplierDevRequestDto(r.getId(), r.getReference(), r.getCompanyId(), r.getCompanyName(), r.getTaxId(),
                r.getContactName(), r.getContactEmail(), r.getContactPhone(), r.getProvince(), r.getSector(),
                r.getEmployees(), r.getTrack().name(), r.getNeeds(), r.getAccessFeeUsd(), r.getFeeStatus().name(),
                r.getFeePaidAt(), r.getProgramFeeUsd(), r.isCustomPricing(), r.getStatus().name(), r.getAdminNotes(),
                r.getHandledById(), r.getHandledAt(), r.getCreatedAt(), r.getUpdatedAt());
    }
}
