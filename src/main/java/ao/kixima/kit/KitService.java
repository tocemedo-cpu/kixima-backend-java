package ao.kixima.kit;

import ao.kixima.catalog.Product;
import ao.kixima.catalog.ProductRepository;
import ao.kixima.common.error.BusinessRuleException;
import ao.kixima.common.error.ForbiddenException;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyRepository;
import ao.kixima.plan.PlanFeatureFlag;
import ao.kixima.plan.PlanService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Espelha backend/src/services/kitService.js — kits e pacotes (funcionalidade de plano). */
@Service
public class KitService {

    public record ItemPedido(String productId, int quantity) {
    }

    private final KitRepository kitRepository;
    private final KitItemRepository kitItemRepository;
    private final ProductRepository productRepository;
    private final CompanyRepository companyRepository;
    private final PlanService planService;

    public KitService(KitRepository kitRepository, KitItemRepository kitItemRepository, ProductRepository productRepository,
                      CompanyRepository companyRepository, PlanService planService) {
        this.kitRepository = kitRepository;
        this.kitItemRepository = kitItemRepository;
        this.productRepository = productRepository;
        this.companyRepository = companyRepository;
        this.planService = planService;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listKits(String supplierCompanyId) {
        List<Kit> kits = kitRepository.findBySupplierIdAndActiveTrueOrderByCreatedAtDesc(supplierCompanyId);
        return comItens(kits);
    }

    @Transactional
    public Map<String, Object> createKit(String supplierCompanyId, String name, String description, List<ItemPedido> items) {
        Company empresa = companyRepository.findById(supplierCompanyId).orElseThrow(() -> new NotFoundException("Empresa"));
        planService.assertFeature(empresa, PlanFeatureFlag.KITS, "Kits e pacotes");
        if (items == null || items.isEmpty()) throw new BusinessRuleException("Um kit precisa de pelo menos um produto.");

        List<String> productIds = items.stream().map(ItemPedido::productId).toList();
        List<Product> products = productRepository.findAllById(productIds);
        if (products.size() != productIds.size() || products.stream().anyMatch(p -> !p.getSupplierId().equals(supplierCompanyId))) {
            throw new BusinessRuleException("Todos os produtos do kit devem pertencer à sua empresa.");
        }
        Kit kit = new Kit(UUID.randomUUID().toString(), supplierCompanyId, name, description == null || description.isBlank() ? null : description, Instant.now());
        kitRepository.save(kit);
        List<KitItem> linhas = new ArrayList<>();
        for (ItemPedido i : items) linhas.add(new KitItem(UUID.randomUUID().toString(), kit.getId(), i.productId(), i.quantity() > 0 ? i.quantity() : 1));
        kitItemRepository.saveAll(linhas);
        return comItens(List.of(kit)).get(0);
    }

    @Transactional
    public Map<String, Object> deleteKit(String id, String supplierCompanyId) {
        Kit kit = kitRepository.findById(id).orElseThrow(() -> new NotFoundException("Kit"));
        if (!kit.getSupplierId().equals(supplierCompanyId)) throw new ForbiddenException("Só pode remover kits da sua empresa.");
        kit.desativar();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        return m;
    }

    /** `include: { items: { include: { product: { select: { id, name, unitPrice, currency } } } } }`. */
    private List<Map<String, Object>> comItens(List<Kit> kits) {
        if (kits.isEmpty()) return List.of();
        List<KitItem> itens = kitItemRepository.findByKitIdIn(kits.stream().map(Kit::getId).toList());
        Map<String, Product> produtos = new HashMap<>();
        for (Product p : productRepository.findAllById(itens.stream().map(KitItem::getProductId).distinct().toList())) produtos.put(p.getId(), p);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Kit k : kits) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", k.getId());
            m.put("supplierId", k.getSupplierId());
            m.put("name", k.getName());
            m.put("description", k.getDescription());
            m.put("active", k.isActive());
            m.put("createdAt", k.getCreatedAt());
            m.put("updatedAt", k.getUpdatedAt());
            List<Map<String, Object>> linhas = new ArrayList<>();
            for (KitItem i : itens) {
                if (!i.getKitId().equals(k.getId())) continue;
                Map<String, Object> li = new LinkedHashMap<>();
                li.put("id", i.getId());
                li.put("kitId", i.getKitId());
                li.put("productId", i.getProductId());
                li.put("quantity", i.getQuantity());
                Product p = produtos.get(i.getProductId());
                Map<String, Object> prod = null;
                if (p != null) {
                    prod = new LinkedHashMap<>();
                    prod.put("id", p.getId());
                    prod.put("name", p.getName());
                    prod.put("unitPrice", p.getUnitPrice());
                    prod.put("currency", p.getCurrency());
                }
                li.put("product", prod);
                linhas.add(li);
            }
            m.put("items", linhas);
            out.add(m);
        }
        return out;
    }
}
