package ao.kixima.marketplace;

import ao.kixima.common.error.ValidationException;
import ao.kixima.marketplace.dto.AddFavoriteRequest;
import ao.kixima.marketplace.dto.FavoriteResultDto;
import ao.kixima.marketplace.dto.SaveSearchRequest;
import ao.kixima.marketplace.dto.SavedSearchDto;
import ao.kixima.security.CurrentUser;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.PersonaRole;
import ao.kixima.security.RequireRole;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.springframework.http.HttpStatus.CREATED;

/**
 * Espelha backend/src/routes/marketplaceRoutes.js — pesquisa, comparação,
 * facetas e fornecedores verificados (Lacunas D.3), favoritos e pesquisas
 * guardadas (M5 lote 2).
 */
@RestController
@RequestMapping("/api/marketplace")
public class MarketplaceController {

    private static final Set<String> KINDS = Set.of("PRODUTO", "SERVICO");
    private static final Set<String> SORTS = Set.of("relevantes", "recentes", "avaliacao", "preco_asc", "preco_desc", "solicitados", "vendidos");

    private final FavoriteService favoriteService;
    private final SavedSearchService savedSearchService;
    private final MarketplaceService marketplaceService;

    public MarketplaceController(FavoriteService favoriteService, SavedSearchService savedSearchService,
                                 MarketplaceService marketplaceService) {
        this.favoriteService = favoriteService;
        this.savedSearchService = savedSearchService;
        this.marketplaceService = marketplaceService;
    }

    // --- Pesquisa (marketplaceSearchSchema + parseFilters) ---------------------

    private static String texto(Map<String, String> q, String campo, int max, Map<String, List<String>> erros) {
        String v = q.get(campo);
        if (v == null) return null;
        if (v.length() > max) erros.computeIfAbsent(campo, k -> new ArrayList<>()).add("String must contain at most " + max + " character(s)");
        return v;
    }

    private static Double numero(Map<String, String> q, String campo, double min, Double max, Map<String, List<String>> erros) {
        String v = q.get(campo);
        if (v == null) return null;
        try {
            double n = Double.parseDouble(v.trim());
            if (n < min || (max != null && n > max) || Double.isNaN(n)) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            erros.computeIfAbsent(campo, k -> new ArrayList<>()).add("Expected number");
            return null;
        }
    }

    private static Integer inteiro(Map<String, String> q, String campo, Integer max, Map<String, List<String>> erros) {
        String v = q.get(campo);
        if (v == null) return null;
        try {
            double n = Double.parseDouble(v.trim());
            if (n != Math.rint(n) || n <= 0 || (max != null && n > max)) throw new NumberFormatException();
            return (int) n;
        } catch (NumberFormatException e) {
            erros.computeIfAbsent(campo, k -> new ArrayList<>()).add("Expected positive integer");
            return null;
        }
    }

    private MarketplaceService.Filtros parseFilters(Map<String, String> q) {
        Map<String, List<String>> erros = new LinkedHashMap<>();
        String texto = texto(q, "q", 120, erros);
        String category = texto(q, "category", 80, erros);
        String kind = q.get("kind");
        if (kind != null && !KINDS.contains(kind)) erros.computeIfAbsent("kind", k -> new ArrayList<>()).add("Invalid enum value");
        String certifications = texto(q, "certifications", 200, erros);
        String availability = texto(q, "availability", 40, erros);
        for (String flag : List.of("verified", "promo")) {
            String v = q.get(flag);
            if (v != null && !"true".equals(v) && !"false".equals(v)) erros.computeIfAbsent(flag, k -> new ArrayList<>()).add("Invalid enum value");
        }
        String country = texto(q, "country", 60, erros);
        String province = texto(q, "province", 60, erros);
        String city = texto(q, "city", 60, erros);
        String specialty = texto(q, "specialty", 80, erros);
        Double minRating = numero(q, "minRating", 0, 5d, erros);
        Double minPrice = numero(q, "minPrice", 0, null, erros);
        Double maxPrice = numero(q, "maxPrice", 0, null, erros);
        String sort = q.get("sort");
        if (sort != null && !SORTS.contains(sort)) erros.computeIfAbsent("sort", k -> new ArrayList<>()).add("Invalid enum value");
        Integer page = inteiro(q, "page", null, erros);
        Integer limit = inteiro(q, "limit", 48, erros);
        if (!erros.isEmpty()) {
            Map<String, Object> flatten = new LinkedHashMap<>();
            flatten.put("formErrors", List.of());
            flatten.put("fieldErrors", erros);
            throw new ValidationException("Parâmetros de pesquisa inválidos.", flatten);
        }
        List<String> certs = new ArrayList<>();
        if (certifications != null) for (String c : certifications.split(",")) if (!c.trim().isEmpty()) certs.add(c.trim());
        CurrentUser user = CurrentUserHolder.get();
        String excludeSupplierId = user.role() == PersonaRole.COMPRADOR ? user.companyId() : null;
        return new MarketplaceService.Filtros(texto == null ? null : texto.trim(), category, kind, certs, availability,
                "true".equals(q.get("verified")), "true".equals(q.get("promo")), country, province, city, specialty, minRating,
                minPrice == null ? null : BigDecimal.valueOf(minPrice), maxPrice == null ? null : BigDecimal.valueOf(maxPrice),
                sort, page, limit, excludeSupplierId);
    }

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam Map<String, String> query) {
        return marketplaceService.search(parseFilters(query), CurrentUserHolder.get().id());
    }

    @GetMapping("/compare")
    @RequireRole({PersonaRole.COMPRADOR})
    public Map<String, Object> compare(@RequestParam(required = false) String productId) {
        if (productId == null || productId.isBlank()) throw new ValidationException("Indique o produto a comparar (productId).");
        return marketplaceService.compareSuppliers(productId, CurrentUserHolder.get().companyId());
    }

    @GetMapping("/facets")
    public Map<String, Object> facets(@RequestParam Map<String, String> query) {
        return marketplaceService.facets(parseFilters(query));
    }

    @GetMapping("/suppliers")
    public List<Map<String, Object>> suppliers() {
        return marketplaceService.verifiedSuppliers(8);
    }

    @GetMapping("/favorites")
    public List<String> listarFavoritos() {
        return favoriteService.listIds(CurrentUserHolder.get().id());
    }

    @PostMapping("/favorites")
    @ResponseStatus(CREATED)
    public FavoriteResultDto adicionarFavorito(@RequestBody AddFavoriteRequest body) {
        if (body.productId() == null || body.productId().isBlank()) {
            throw new ValidationException("Favorito inválido.");
        }
        return favoriteService.add(CurrentUserHolder.get().id(), body.productId());
    }

    @DeleteMapping("/favorites/{productId}")
    public FavoriteResultDto removerFavorito(@PathVariable String productId) {
        return favoriteService.remove(CurrentUserHolder.get().id(), productId);
    }

    @GetMapping("/saved-searches")
    public List<SavedSearchDto> listarPesquisasGuardadas() {
        return savedSearchService.listar(CurrentUserHolder.get().id());
    }

    @PostMapping("/saved-searches")
    @ResponseStatus(CREATED)
    public SavedSearchDto guardarPesquisa(@RequestBody(required = false) SaveSearchRequest body) {
        String label = body == null ? null : body.label();
        String query = body == null ? null : body.query();
        return savedSearchService.criar(CurrentUserHolder.get().id(), label, query);
    }

    @DeleteMapping("/saved-searches/{id}")
    public Map<String, String> removerPesquisaGuardada(@PathVariable String id) {
        savedSearchService.remover(CurrentUserHolder.get().id(), id);
        return Map.of("id", id);
    }
}
