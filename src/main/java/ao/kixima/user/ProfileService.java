package ao.kixima.user;

import ao.kixima.common.error.NotFoundException;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyRepository;
import ao.kixima.company.CompanyType;
import ao.kixima.company.SupplierToKiximaPolicyRepository;
import ao.kixima.po.PoStatus;
import ao.kixima.po.PurchaseOrder;
import ao.kixima.po.PurchaseOrderItem;
import ao.kixima.po.PurchaseOrderRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Espelha profileService.js — perfil pessoal, mesmo formato para todas as
 * personas: utilizador, IDENTIFICAÇÃO da empresa (nunca a ficha completa) e um
 * resumo adequado ao papel + atividade recente.
 */
@Service
public class ProfileService {

    static final Set<PoStatus> RECEIVED = Set.of(PoStatus.RECEBIDA_CONFORME, PoStatus.RECEBIDA_COM_DIVERGENCIA, PoStatus.CONCLUIDA);

    public record UserRef(String id, String name, String email, String role, String avatarUrl, Instant createdAt) {
    }

    /** Só a identificação da empresa — a ficha completa é a tela de Perfil da Empresa (Company Admin). */
    public record CompanyRef(String id, String name, String taxId, String type, String status, String contactEmail, String contactPhone,
                             String address, String city, String province, String country, String logoUrl, boolean verified) {
        public static CompanyRef de(Company c) {
            return new CompanyRef(c.getId(), c.getName(), c.getTaxId(), c.getType().name(), c.getStatus().name(), c.getContactEmail(),
                    c.getContactPhone(), c.getAddress(), c.getCity(), c.getProvince(), c.getCountry(), c.getLogoUrl(), c.isVerified());
        }
    }

    /** `money: true` só existe nos cartões de valor (profileService.js); os restantes não têm a chave. */
    public record Card(String icon, String tone, String label, Object value,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Boolean money, String sub) {
    }

    public record Recent(String reference, String party, String status, Instant at) {
    }

    /** `company` é sempre presente (null para o Admin do Sistema) — daí não excluir nulos. */
    public record Profile(UserRef user, CompanyRef company, String kind, List<Card> cards, List<Recent> recent) {
    }

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final SupplierToKiximaPolicyRepository supplierPolicyRepository;

    public ProfileService(UserRepository userRepository, CompanyRepository companyRepository,
                          PurchaseOrderRepository purchaseOrderRepository, SupplierToKiximaPolicyRepository supplierPolicyRepository) {
        this.userRepository = userRepository;
        this.companyRepository = companyRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.supplierPolicyRepository = supplierPolicyRepository;
    }

    static int itemsCount(PurchaseOrder po) {
        return po.getItems().stream().mapToInt(PurchaseOrderItem::getQuantity).sum();
    }

    static Instant inicioDoAno() {
        return LocalDate.now(ZoneId.systemDefault()).withDayOfYear(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    @Transactional(readOnly = true)
    public Profile getProfile(String userId, String companyId) {
        User u = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("Utilizador"));
        UserRef user = new UserRef(u.getId(), u.getName(), u.getEmail(), u.getRole().name(), u.getAvatarUrl(), u.getCreatedAt());
        Company company = companyId == null ? null : companyRepository.findById(companyId).orElse(null);

        // Admin do Sistema — sem empresa: estatísticas da plataforma.
        if (company == null) {
            List<Card> cards = List.of(
                    new Card("building", "info", "Empresas", companyRepository.count(), null, "Registadas"),
                    new Card("orders", "success", "Ordens (plataforma)", purchaseOrderRepository.count(), null, "Total"),
                    new Card("policy", "pending", "Apólices", supplierPolicyRepository.count(), null, "Fornecedores"),
                    new Card("users", "info", "Utilizadores", userRepository.count(), null, "Total"));
            return new Profile(user, null, "admin", cards, List.of());
        }

        boolean isSupplier = company.getType() == CompanyType.FORNECEDOR;
        List<PurchaseOrder> orders = isSupplier
                ? purchaseOrderRepository.findBySupplierCompanyIdOrderByUpdatedAtDesc(companyId)
                : purchaseOrderRepository.findByBuyerCompanyIdOrderByUpdatedAtDesc(companyId);
        Instant yearStart = inicioDoAno();
        List<PurchaseOrder> yearOrders = orders.stream().filter(o -> !o.getCreatedAt().isBefore(yearStart)).toList();
        Set<String> counterpart = new HashSet<>();
        orders.forEach(o -> counterpart.add(isSupplier ? o.getBuyerCompanyId() : o.getSupplierCompanyId()));
        int itemsMoved = orders.stream().filter(o -> RECEIVED.contains(o.getStatus())).mapToInt(ProfileService::itemsCount).sum();
        BigDecimal totalValue = yearOrders.stream().map(PurchaseOrder::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Card> cards = isSupplier
                ? List.of(
                new Card("orders", "info", "Ordens Recebidas", yearOrders.size(), null, "Este ano"),
                new Card("payment", "success", "Valor Total Vendido", ao.kixima.common.Decimais.numero(totalValue), true, "Este ano"),
                new Card("suppliers", "info", "Clientes", counterpart.size(), null, "Com transações"),
                new Card("reception", "pending", "Itens Entregues", itemsMoved, null, "Total"))
                : List.of(
                new Card("orders", "info", "Ordens de Compra", yearOrders.size(), null, "Este ano"),
                new Card("payment", "success", "Valor Total Comprado", ao.kixima.common.Decimais.numero(totalValue), true, "Este ano"),
                new Card("suppliers", "info", "Fornecedores", counterpart.size(), null, "Com transações"),
                new Card("reception", "pending", "Itens Recebidos", itemsMoved, null, "Total"));

        List<Recent> recent = orders.stream().limit(5).map(o -> new Recent(o.getReference(),
                isSupplier ? nome(o.getBuyerCompany()) : nome(o.getSupplierCompany()), o.getStatus().name(), o.getUpdatedAt())).toList();
        return new Profile(user, CompanyRef.de(company), isSupplier ? "supplier" : "buyer", cards, recent);
    }

    private static String nome(Company c) {
        return c == null ? null : c.getName();
    }
}
