package ao.kixima.marketplace;

import ao.kixima.catalog.CatalogService;
import ao.kixima.catalog.Product;
import ao.kixima.catalog.ProductRepository;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyPlan;
import ao.kixima.plan.PlanFeatureFlag;
import ao.kixima.plan.PlanService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Espelha backend/src/services/marketplaceService.js — pesquisa paginada e
 * filtrada, facetas, fornecedores verificados e comparação de fornecedores.
 *
 * Os filtros são traduzidos para SQL nativo (JdbcTemplate) porque envolvem
 * as colunas `search_text` (mantidas por gatilho na base), `tags`/`
 * certifications` (text[]) e a ordenação pelo `search_rank` do fornecedor —
 * o mesmo `where` do Prisma, predicado a predicado. A hidratação dos
 * produtos da página é feita pelo JPA para devolver o `Product` inteiro.
 */
@Service
public class MarketplaceService {

    /** Espelha marketplaceSearchSchema já saneado + o que parseFilters acrescenta. */
    public record Filtros(String q, String category, String kind, List<String> certifications, String availability,
                          boolean verified, boolean promo, String country, String province, String city, String specialty,
                          Double minRating, BigDecimal minPrice, BigDecimal maxPrice, String sort, Integer page, Integer limit,
                          String excludeSupplierId) {
        Filtros sem(String campo) {
            return new Filtros("q".equals(campo) ? null : q, "category".equals(campo) ? null : category, "kind".equals(campo) ? null : kind,
                    "certifications".equals(campo) ? List.of() : certifications, availability, verified, promo,
                    "country".equals(campo) ? null : country, province, city, specialty, minRating,
                    "price".equals(campo) ? null : minPrice, "price".equals(campo) ? null : maxPrice, sort, page, limit, excludeSupplierId);
        }
    }

    private static final Map<String, String> SORTS = Map.of(
            "relevantes", "c.search_rank DESC, p.review_count DESC, p.rating DESC, p.created_at DESC",
            "recentes", "p.created_at DESC",
            "avaliacao", "p.rating DESC, p.review_count DESC",
            "preco_asc", "p.unit_price ASC",
            "preco_desc", "p.unit_price DESC",
            "solicitados", "p.view_count DESC",
            "vendidos", "p.review_count DESC, p.view_count DESC"); // proxy até haver contador de vendas

    /** O MESMO mapa de acentos que a base usa em kixima_normalizar() — caractere a caractere. */
    private static final String ACENTOS = "áàâãäéèêëíìîïóòôõöúùûüçñ";
    private static final String SEM_ACENTOS = "aaaaaeeeeiiiiooooouuuucn";

    private final JdbcTemplate jdbcTemplate;
    private final ProductRepository productRepository;
    private final FavoriteRepository favoriteRepository;
    private final CatalogService catalogService;
    private final PlanService planService;
    private final ObjectMapper objectMapper;

    public MarketplaceService(JdbcTemplate jdbcTemplate, ProductRepository productRepository, FavoriteRepository favoriteRepository,
                              CatalogService catalogService, PlanService planService, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.productRepository = productRepository;
        this.favoriteRepository = favoriteRepository;
        this.catalogService = catalogService;
        this.planService = planService;
        this.objectMapper = objectMapper;
    }

    public static String normalizarParaPesquisa(String texto) {
        String s = texto == null ? "" : texto.toLowerCase();
        StringBuilder saida = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int idx = ACENTOS.indexOf(c);
            saida.append(idx == -1 ? c : SEM_ACENTOS.charAt(idx));
        }
        return saida.toString();
    }

    // --- where ------------------------------------------------------------------

    private record Where(String sql, List<Object> params) {
    }

    private static Where buildWhere(Filtros f) {
        List<String> cond = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        cond.add("p.active = true");
        if (f.kind() != null) {
            cond.add("p.kind = ?::\"ProductKind\"");
            params.add(f.kind());
        }
        if (f.category() != null) {
            cond.add("p.category = ?");
            params.add(f.category());
        }
        if (f.availability() != null) {
            cond.add("p.availability = ?");
            params.add(f.availability());
        }
        if (f.country() != null) {
            cond.add("p.country = ?");
            params.add(f.country());
        }
        if (f.province() != null) {
            cond.add("p.province = ?");
            params.add(f.province());
        }
        if (f.city() != null) {
            cond.add("p.city = ?");
            params.add(f.city());
        }
        if (f.specialty() != null) {
            cond.add("p.specialty = ?");
            params.add(f.specialty());
        }
        if (f.minRating() != null) {
            cond.add("p.rating >= ?");
            params.add(f.minRating());
        }
        if (f.minPrice() != null) {
            cond.add("p.unit_price >= ?");
            params.add(f.minPrice());
        }
        if (f.maxPrice() != null) {
            cond.add("p.unit_price <= ?");
            params.add(f.maxPrice());
        }
        if (f.certifications() != null && !f.certifications().isEmpty()) {
            cond.add("p.certifications && string_to_array(?, ',')");
            params.add(String.join(",", f.certifications()));
        }
        if (f.promo()) cond.add("p.promo_price IS NOT NULL");
        if (f.verified()) cond.add("c.verified = true");
        if (f.excludeSupplierId() != null) {
            cond.add("p.supplier_id <> ?");
            params.add(f.excludeSupplierId());
        }
        if (f.q() != null && !f.q().isBlank()) {
            String q = normalizarParaPesquisa(f.q());
            cond.add("(p.search_text LIKE ? OR ? = ANY(p.tags) OR c.search_text LIKE ?)");
            params.add("%" + q + "%");
            params.add(f.q());
            params.add("%" + q + "%");
        }
        return new Where(String.join(" AND ", cond), params);
    }

    private static final String FROM = " FROM products p JOIN companies c ON c.id = p.supplier_id WHERE ";

    // --- search -------------------------------------------------------------------

    /** `{ ...supplier sem plan, destaque }` — o selo vem da matriz de planos, não da posição na pesquisa. */
    private Map<String, Object> comSelo(Company c) {
        if (c == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("verified", c.isVerified());
        m.put("logoUrl", c.getLogoUrl());
        m.put("searchRank", c.getSearchRank());
        CompanyPlan plan = c.getPlan();
        m.put("destaque", planService.hasFeature(plan, PlanFeatureFlag.SELO));
        return m;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> search(Filtros filters, String userId) {
        int page = Math.max(1, filters.page() == null ? 1 : filters.page());
        int limit = Math.min(48, Math.max(1, filters.limit() == null ? 16 : filters.limit()));
        Where where = buildWhere(filters);
        String orderBy = SORTS.getOrDefault(filters.sort() == null ? "relevantes" : filters.sort(), SORTS.get("relevantes"));

        long total = jdbcTemplate.queryForObject("SELECT count(*)" + FROM + where.sql(), Long.class, where.params().toArray());
        List<Object> params = new ArrayList<>(where.params());
        params.add(limit);
        params.add((page - 1) * limit);
        List<String> ids = jdbcTemplate.queryForList("SELECT p.id" + FROM + where.sql() + " ORDER BY " + orderBy + ", p.id LIMIT ? OFFSET ?",
                String.class, params.toArray());

        Map<String, Product> porId = new HashMap<>();
        for (Product p : productRepository.findAllById(ids)) porId.put(p.getId(), p);
        Set<String> favSet = new HashSet<>();
        if (userId != null && !ids.isEmpty()) {
            for (Favorite f : favoriteRepository.findByUserIdAndProductIdIn(userId, ids)) favSet.add(f.getProductId());
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (String id : ids) {
            Product p = porId.get(id);
            if (p == null) continue;
            Map<String, Object> item = objectMapper.convertValue(catalogService.toDto(p, null, false), new TypeReference<LinkedHashMap<String, Object>>() {
            });
            item.put("supplier", comSelo(p.getSupplier()));
            item.put("isFavorite", favSet.contains(p.getId()));
            items.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", total);
        out.put("page", page);
        out.put("pages", Math.max(1, (int) Math.ceil(total / (double) limit)));
        out.put("limit", limit);
        return out;
    }

    // --- facets -------------------------------------------------------------------

    private List<Map<String, Object>> contagem(String coluna, Where where, String ordem) {
        return jdbcTemplate.query("SELECT " + coluna + " AS name, count(*) AS count" + FROM + where.sql() + " GROUP BY " + coluna + ordem,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", rs.getString("name"));
                    m.put("count", rs.getLong("count"));
                    return m;
                }, where.params().toArray());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> facets(Filtros filters) {
        List<Map<String, Object>> byCategory = contagem("p.category", buildWhere(filters.sem("category")), " ORDER BY count(*) DESC, p.category ASC");
        List<Map<String, Object>> byKind = contagem("p.kind::text", buildWhere(filters.sem("kind")), " ORDER BY p.kind::text ASC");
        List<Map<String, Object>> byCountry = contagem("p.country", buildWhere(filters.sem("country")), " ORDER BY p.country ASC").stream()
                .filter(c -> c.get("name") != null).toList();

        Where semCerts = buildWhere(filters.sem("certifications"));
        List<Map<String, Object>> certifications = jdbcTemplate.query(
                "SELECT cert AS name, count(*) AS count FROM (SELECT unnest(p.certifications) AS cert" + FROM + semCerts.sql() + ") t GROUP BY cert ORDER BY count(*) DESC, cert ASC",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", rs.getString("name"));
                    m.put("count", rs.getLong("count"));
                    return m;
                }, semCerts.params().toArray());

        Where semPreco = buildWhere(filters.sem("price"));
        Map<String, Object> bounds = jdbcTemplate.queryForMap("SELECT min(p.unit_price) AS min, max(p.unit_price) AS max" + FROM + semPreco.sql(), semPreco.params().toArray());
        Map<String, Object> priceBounds = new LinkedHashMap<>();
        priceBounds.put("min", bounds.get("min") == null ? 0 : ((BigDecimal) bounds.get("min")).doubleValue());
        priceBounds.put("max", bounds.get("max") == null ? 0 : ((BigDecimal) bounds.get("max")).doubleValue());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categories", byCategory);
        out.put("kinds", byKind);
        out.put("countries", byCountry);
        out.put("certifications", certifications);
        out.put("priceBounds", priceBounds);
        return out;
    }

    // --- verifiedSuppliers ----------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> verifiedSuppliers(int limit) {
        List<Map<String, Object>> suppliers = jdbcTemplate.query(
                "SELECT c.id, c.name, c.logo_url, c.city, c.country, "
                        + "(SELECT avg(p.rating) FROM products p WHERE p.supplier_id = c.id AND p.active = true) AS rating, "
                        + "(SELECT count(*) FROM products p WHERE p.supplier_id = c.id AND p.active = true) AS product_count "
                        + "FROM companies c WHERE c.type = 'FORNECEDOR'::\"CompanyType\" AND c.verified = true AND c.status = 'APROVADA'::\"CompanyStatus\" "
                        + "ORDER BY c.created_at ASC LIMIT ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("name", rs.getString("name"));
                    m.put("logoUrl", rs.getString("logo_url"));
                    m.put("city", rs.getString("city"));
                    m.put("country", rs.getString("country"));
                    double media = rs.getDouble("rating");
                    m.put("rating", rs.wasNull() || media == 0 ? null : Math.round(media * 10) / 10.0);
                    m.put("productCount", rs.getLong("product_count"));
                    return m;
                }, limit);
        List<Map<String, Object>> ordenados = new ArrayList<>(suppliers);
        ordenados.sort((a, b) -> Double.compare(valor(b.get("rating")), valor(a.get("rating"))));
        return ordenados;
    }

    private static double valor(Object o) {
        return o == null ? 0 : ((Number) o).doubleValue();
    }

    // --- compareSuppliers ------------------------------------------------------------

    private static String normName(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase().replaceAll("\\s+", " ").trim();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> compareSuppliers(String productId, String excludeSupplierId) {
        Product base = productRepository.findById(productId).orElseThrow(() -> new NotFoundException("Produto"));

        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT p.id FROM products p WHERE p.active = true AND (");
        if (base.getUnspscCode() != null) {
            sql.append("p.unspsc_code = ? OR ");
            params.add(base.getUnspscCode());
        }
        sql.append("lower(p.name) = lower(?))");
        params.add(base.getName());
        if (excludeSupplierId != null) {
            sql.append(" AND p.supplier_id <> ?");
            params.add(excludeSupplierId);
        }
        sql.append(" ORDER BY p.unit_price ASC, p.id LIMIT 40");
        List<String> ids = jdbcTemplate.queryForList(sql.toString(), String.class, params.toArray());
        Map<String, Product> porId = new HashMap<>();
        for (Product p : productRepository.findAllById(ids)) porId.put(p.getId(), p);

        String baseName = normName(base.getName());
        Map<String, Map<String, Object>> bySupplier = new LinkedHashMap<>();
        for (String id : ids) {
            Product o = porId.get(id);
            if (o == null) continue;
            boolean mesmo = (base.getUnspscCode() != null && base.getUnspscCode().equals(o.getUnspscCode())) || normName(o.getName()).equals(baseName);
            if (!mesmo) continue;
            double effectivePrice = (o.getPromoPrice() != null ? o.getPromoPrice() : o.getUnitPrice() == null ? BigDecimal.ZERO : o.getUnitPrice()).doubleValue();
            Map<String, Object> oferta = oferta(o, effectivePrice);
            String supplierId = o.getSupplierId();
            Map<String, Object> cur = bySupplier.get(supplierId);
            if (cur == null || effectivePrice < (double) cur.get("effectivePrice")) bySupplier.put(supplierId, oferta);
        }
        List<Map<String, Object>> offers = new ArrayList<>(bySupplier.values());
        offers.sort((a, b) -> Double.compare((double) a.get("effectivePrice"), (double) b.get("effectivePrice")));
        if (offers.size() > 5) offers = new ArrayList<>(offers.subList(0, 5));

        Map<String, Object> baseOut = new LinkedHashMap<>();
        baseOut.put("name", base.getName());
        baseOut.put("unspscCode", base.getUnspscCode());
        baseOut.put("unspscTitle", base.getUnspscTitle());
        baseOut.put("category", base.getCategory());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("base", baseOut);
        out.put("offers", offers);
        out.put("count", offers.size());
        return out;
    }

    /** COMPARE_SELECT + effectivePrice. */
    private static Map<String, Object> oferta(Product o, double effectivePrice) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.getId());
        m.put("name", o.getName());
        m.put("unitPrice", o.getUnitPrice());
        m.put("promoPrice", o.getPromoPrice());
        m.put("currency", o.getCurrency());
        m.put("leadTimeDays", o.getLeadTimeDays());
        m.put("material", o.getMaterial());
        m.put("warranty", o.getWarranty());
        m.put("standard", o.getStandard());
        m.put("keySpec", o.getKeySpec());
        m.put("certifications", o.getCertifications());
        m.put("countryOfOrigin", o.getCountryOfOrigin());
        m.put("incoterm", o.getIncoterm());
        m.put("availability", o.getAvailability());
        m.put("rating", o.getRating());
        m.put("reviewCount", o.getReviewCount());
        m.put("unspscCode", o.getUnspscCode());
        m.put("category", o.getCategory());
        Company c = o.getSupplier();
        Map<String, Object> supplier = null;
        if (c != null) {
            supplier = new LinkedHashMap<>();
            supplier.put("id", c.getId());
            supplier.put("name", c.getName());
            supplier.put("verified", c.isVerified());
            supplier.put("city", c.getCity());
            supplier.put("country", c.getCountry());
        }
        m.put("supplier", supplier);
        m.put("effectivePrice", effectivePrice);
        return m;
    }
}
