package ao.kixima.marketplace;

import ao.kixima.catalog.CatalogService;
import ao.kixima.catalog.ReviewService;
import ao.kixima.catalog.dto.ProductDto;
import ao.kixima.catalog.dto.ReviewDto;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.common.error.ValidationException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Montra pública do catálogo — SÓ LEITURA, sem sessão ({@link ao.kixima.security.PublicPaths}).
 *
 * <p>Serve a homepage e as páginas de categoria/produto para visitantes: pesquisa, facetas, fornecedoras verificadas e
 * a ficha de um produto. Reutiliza a mesma pesquisa do marketplace autenticado, mas:
 * <ul>
 *   <li>só mostra produtos ativos de fornecedoras <b>APROVADAS</b>;</li>
 *   <li>devolve uma lista BRANCA de campos — nunca SKU, código do fabricante, notas internas do fornecedor, stock exato,
 *       armazém, stock mínimo, contadores de visualização, estado interno, nem dados do utilizador (favoritos);</li>
 *   <li>não conta visualizações nem altera nada;</li>
 *   <li>mostra o preço unitário/promocional quando existe (é o que a montra apresenta como «preço público»); sem preço o
 *       campo vem {@code null} e a interface mostra «Preço sob cotação».</li>
 * </ul>
 * Operações empresariais (cotações, requisições, ordens, favoritos…) continuam exclusivas do marketplace autenticado.
 */
@RestController
@RequestMapping("/api/public/catalog")
public class PublicCatalogController {

    /** Campos de um produto que a montra pública pode revelar. Tudo o resto é descartado. */
    static final Set<String> CAMPOS_PRODUTO = Set.of(
            "id", "supplierId", "supplier", "name", "slug", "kind", "category", "subcategory", "brand", "manufacturer", "model",
            "countryOfOrigin", "description", "fullDescription", "applications", "benefits", "keywords", "unspscTitle",
            "keySpec", "standard", "warranty", "incoterm", "material", "weight", "height", "width", "length",
            "pressure", "temperature", "power", "voltage", "measurementUnit",
            "unitPrice", "promoPrice", "currency", "minQuantity", "maxQuantity", "leadTimeDays", "availability",
            "specialty", "city", "province", "country", "certifications", "tags", "rating", "reviewCount", "imageUrl",
            "createdAt", "images", "documents");
    static final Set<String> CAMPOS_FORNECEDORA = Set.of("id", "name", "verified", "logoUrl");
    private static final int MAXIMO_FORNECEDORAS = 24;

    private final MarketplaceService marketplaceService;
    private final CatalogService catalogService;
    private final ReviewService reviewService;
    private final ObjectMapper objectMapper;

    public PublicCatalogController(MarketplaceService marketplaceService, CatalogService catalogService,
                                   ReviewService reviewService, ObjectMapper objectMapper) {
        this.marketplaceService = marketplaceService;
        this.catalogService = catalogService;
        this.reviewService = reviewService;
        this.objectMapper = objectMapper;
    }

    private static void podeGuardarEmCache(HttpServletResponse res) {
        res.setHeader("Cache-Control", "public, max-age=30");
    }

    private static Map<String, Object> filtrar(Map<String, Object> origem, Set<String> permitidos) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String campo : permitidos.stream().sorted().toList()) {
            if (origem.containsKey(campo)) out.put(campo, origem.get(campo));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> vistaPublica(Map<String, Object> produto) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : produto.entrySet()) {
            if (CAMPOS_PRODUTO.contains(e.getKey())) out.put(e.getKey(), e.getValue());
        }
        if (out.get("supplier") instanceof Map<?, ?> f) out.put("supplier", filtrar((Map<String, Object>) f, CAMPOS_FORNECEDORA));
        return out;
    }

    private Map<String, Object> comoMapa(Object dto) {
        return objectMapper.convertValue(dto, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    /** Mesmos filtros e ordenações do marketplace autenticado (q, category, kind, certifications, sort, page, limit…). */
    @GetMapping("/search")
    @SuppressWarnings("unchecked")
    public Map<String, Object> search(@RequestParam Map<String, String> query, HttpServletResponse res) {
        podeGuardarEmCache(res);
        Map<String, Object> resultado = marketplaceService.search(MarketplaceController.parseFilters(query, null, true), null);
        List<Map<String, Object>> itens = new ArrayList<>();
        for (Object item : (List<Object>) resultado.get("items")) itens.add(vistaPublica((Map<String, Object>) item));
        Map<String, Object> out = new LinkedHashMap<>(resultado);
        out.put("items", itens);
        return out;
    }

    /** Contagens por categoria, tipo, país, certificação e limites de preço (agregados — não expõem produtos). */
    @GetMapping("/facets")
    public Map<String, Object> facets(@RequestParam Map<String, String> query, HttpServletResponse res) {
        podeGuardarEmCache(res);
        return marketplaceService.facets(MarketplaceController.parseFilters(query, null, true));
    }

    /** Fornecedoras verificadas e aprovadas, com avaliação média e número de produtos ativos. */
    @GetMapping("/suppliers")
    public List<Map<String, Object>> suppliers(@RequestParam(required = false) Integer limit, HttpServletResponse res) {
        podeGuardarEmCache(res);
        if (limit != null && (limit < 1 || limit > MAXIMO_FORNECEDORAS)) {
            throw new ValidationException("Parâmetros de pesquisa inválidos.", Map.of("formErrors", List.of(),
                    "fieldErrors", Map.of("limit", List.of("Expected integer between 1 and " + MAXIMO_FORNECEDORAS))));
        }
        return marketplaceService.verifiedSuppliers(limit == null ? 8 : limit);
    }

    private ProductDto produtoVisivel(String slug) {
        ProductDto p = catalogService.getProductBySlug(slug);
        boolean fornecedoraAprovada = p.supplier() != null && "APROVADA".equals(String.valueOf(p.supplier().get("status")));
        if (!p.active() || !fornecedoraAprovada) throw new NotFoundException("Produto");
        return p;
    }

    /** Ficha de um produto/serviço ativo (404 se não existir, estiver retirado ou a fornecedora não estiver aprovada). */
    @GetMapping("/products/{slug}")
    public Map<String, Object> product(@PathVariable String slug, HttpServletResponse res) {
        podeGuardarEmCache(res);
        return vistaPublica(comoMapa(produtoVisivel(slug)));
    }

    /** Avaliações do produto. O autor aparece só como «Nome I.»; nunca o identificador da conta. */
    @GetMapping("/products/{slug}/reviews")
    public List<Map<String, Object>> reviews(@PathVariable String slug, HttpServletResponse res) {
        podeGuardarEmCache(res);
        ProductDto p = produtoVisivel(slug);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReviewDto r : reviewService.listForProduct(p.id())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("authorName", autorAbreviado(r.authorName()));
            m.put("rating", r.rating());
            m.put("comment", r.comment());
            m.put("createdAt", r.createdAt());
            out.add(m);
        }
        return out;
    }

    static String autorAbreviado(String nome) {
        if (nome == null || nome.isBlank()) return "Cliente";
        String[] partes = nome.trim().split("\\s+");
        if (partes.length == 1) return partes[0];
        return partes[0] + " " + Character.toUpperCase(partes[partes.length - 1].charAt(0)) + ".";
    }
}
