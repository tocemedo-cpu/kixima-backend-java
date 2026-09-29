package ao.kixima.catalog;

import ao.kixima.apikey.ApiKeyService;
import ao.kixima.apikey.dto.ApiKeyCreatedDto;
import ao.kixima.apikey.dto.ApiKeyDto;
import ao.kixima.apikey.dto.CreateApiKeyRequest;
import ao.kixima.apikey.dto.RevokedApiKeyDto;
import ao.kixima.audit.Actor;
import ao.kixima.audit.AuditService;
import ao.kixima.catalog.dto.AddReviewRequest;
import ao.kixima.catalog.dto.CreateStockMovementRequest;
import ao.kixima.catalog.dto.ProductDto;
import ao.kixima.catalog.dto.ProductPayload;
import ao.kixima.catalog.dto.ReviewDto;
import ao.kixima.catalog.dto.ReviewSummaryDto;
import ao.kixima.catalog.dto.StockMovementDto;
import ao.kixima.catalog.dto.StockMovementListItemDto;
import ao.kixima.catalog.dto.SupplierDocumentsResponse;
import ao.kixima.catalog.dto.UpdateStockRequest;
import ao.kixima.common.error.ErrorResponse;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.common.error.ValidationException;
import ao.kixima.common.pagination.PaginaResposta;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyRepository;
import ao.kixima.security.CurrentUser;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.PersonaRole;
import ao.kixima.security.RequireRole;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.WebUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ao.kixima.security.PersonaRole.COMPANY_ADMIN;
import static ao.kixima.security.PersonaRole.COMPRADOR;
import static ao.kixima.security.PersonaRole.FORNECEDOR;
import static org.springframework.http.HttpStatus.CREATED;

/**
 * Espelha backend/src/controllers/catalogController.js + o troço de leitura
 * de backend/src/routes/catalogRoutes.js. Toda a rota `/api/catalog/**`
 * exige sessão (`router.use(authenticate)` no Node — confirmado por leitura
 * directa, não é pública apesar de não ter `requireRole`), o que já é o
 * comportamento por omissão de {@link ao.kixima.security.PublicPaths}.
 */
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final CatalogService catalogService;
    private final ReviewService reviewService;
    private final ApiKeyService apiKeyService;
    private final CompanyRepository companyRepository;
    private final AuditService auditService;
    private final CatalogImportService catalogImportService;
    private final ObjectMapper objectMapper;

    public CatalogController(CatalogService catalogService, ReviewService reviewService, ApiKeyService apiKeyService,
                              CompanyRepository companyRepository, AuditService auditService,
                              CatalogImportService catalogImportService, ObjectMapper objectMapper) {
        this.catalogService = catalogService;
        this.reviewService = reviewService;
        this.apiKeyService = apiKeyService;
        this.companyRepository = companyRepository;
        this.auditService = auditService;
        this.catalogImportService = catalogImportService;
        this.objectMapper = objectMapper;
    }

    // --- Escrita da ficha e media (Lacunas D.2) --------------------------------
    // `productMedia`/`productMediaAppend` do Node: os campos de ficheiro que o
    // multer aceita, por nome — capa, galeria e um campo por tipo de documento.

    /**
     * O corpo tal como o Node o vê: campos multipart (uma ficha com ficheiros)
     * ou JSON (a mesma ficha sem ficheiros) — express.json e o multer coexistem.
     */
    /** O pedido chega embrulhado (Spring Security, caching) — o multipart está por baixo. */
    private static MultipartHttpServletRequest multipart(HttpServletRequest req) {
        return WebUtils.getNativeRequest(req, MultipartHttpServletRequest.class);
    }

    private Map<String, Object> corpo(HttpServletRequest req) {
        String contentType = req.getContentType() == null ? "" : req.getContentType();
        if (multipart(req) != null || contentType.startsWith("multipart/")) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<String, String[]> e : req.getParameterMap().entrySet()) {
                m.put(e.getKey(), e.getValue().length == 0 ? null : e.getValue()[0]);
            }
            return m;
        }
        try {
            byte[] raw = req.getInputStream().readAllBytes();
            if (raw.length == 0) return Map.of();
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
        } catch (IOException e) {
            throw new ValidationException("Dados inválidos.");
        }
    }

    private static List<MultipartFile> ficheiros(HttpServletRequest req, String campo) {
        MultipartHttpServletRequest mp = multipart(req);
        if (mp == null) return List.of();
        return mp.getFiles(campo).stream().filter(f -> !f.isEmpty()).toList();
    }

    /** Capa (só na criação) + galeria + documentos por tipo, cada um pelo filtro do multer correspondente. */
    private CatalogService.Media mediaDe(HttpServletRequest req, boolean comCapa) {
        MultipartFile mainImage = null;
        if (comCapa) {
            List<MultipartFile> capa = ficheiros(req, "mainImage");
            if (!capa.isEmpty()) mainImage = UploadFilters.imagem(capa.get(0), UploadFilters.LIMITE_MEDIA);
        }
        List<MultipartFile> gallery = new ArrayList<>();
        for (MultipartFile g : ficheiros(req, "gallery")) gallery.add(UploadFilters.imagem(g, UploadFilters.LIMITE_MEDIA));
        List<CatalogService.Documento> documents = new ArrayList<>();
        for (ProductDocType type : ProductDocType.values()) {
            for (MultipartFile f : ficheiros(req, type.name())) {
                documents.add(new CatalogService.Documento(type, UploadFilters.documento(f, UploadFilters.LIMITE_MEDIA)));
            }
        }
        return new CatalogService.Media(mainImage, gallery, documents);
    }

    @PostMapping
    @RequireRole({FORNECEDOR, COMPANY_ADMIN})
    public ResponseEntity<ProductDto> create(HttpServletRequest req) {
        CatalogService.Media media = mediaDe(req, true);
        ProductPayload data = ProductPayload.parse(corpo(req), true);
        CurrentUser user = CurrentUserHolder.get();
        ProductDto product = catalogService.createProduct(user.companyId(), data, media);
        auditService.recordSafe(new AuditService.Entry(auditService.actorFrom(user, req), "CATALOGO_PRODUTO_CRIADO", "Product",
                product.id(), product.name(), null));
        return ResponseEntity.status(HttpStatus.CREATED).body(product);
    }

    @PostMapping("/import")
    @RequireRole({FORNECEDOR, COMPANY_ADMIN})
    public ResponseEntity<?> importCatalog(@RequestParam(value = "file", required = false) MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(ErrorResponse.of("NO_FILE", "Envie um ficheiro Excel (.xlsx)."));
        }
        UploadFilters.folha(file);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ValidationException("Não foi possível ler o ficheiro enviado.");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(catalogImportService.importCatalog(bytes, CurrentUserHolder.get().companyId()));
    }

    @PutMapping("/{id}")
    @RequireRole({FORNECEDOR, COMPANY_ADMIN})
    public ProductDto update(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        ProductPayload data = ProductPayload.parse(body, false);
        CurrentUser user = CurrentUserHolder.get();
        ProductDto product = catalogService.updateProduct(id, user.companyId(), data);
        auditService.recordSafe(new AuditService.Entry(auditService.actorFrom(user, req), "CATALOGO_PRODUTO_ATUALIZADO", "Product",
                product.id(), product.name(), Map.of("camposAlterados", data.camposAlterados())));
        return product;
    }

    @PostMapping("/{id}/image")
    @RequireRole({FORNECEDOR, COMPANY_ADMIN})
    public ResponseEntity<?> uploadImage(@PathVariable String id, @RequestParam(value = "image", required = false) MultipartFile image) {
        if (image == null || image.isEmpty()) {
            return ResponseEntity.badRequest().body(ErrorResponse.of("NO_FILE", "Nenhuma imagem enviada."));
        }
        UploadFilters.imagem(image, UploadFilters.LIMITE_IMAGEM);
        return ResponseEntity.ok(catalogService.setProductImage(id, CurrentUserHolder.get().companyId(), image));
    }

    @PostMapping("/{id}/media")
    @RequireRole({FORNECEDOR, COMPANY_ADMIN})
    public ProductDto addMedia(@PathVariable String id, HttpServletRequest req) {
        return catalogService.addProductMedia(id, CurrentUserHolder.get().companyId(), mediaDe(req, false));
    }

    @DeleteMapping("/{id}/images/{imageId}")
    @RequireRole({FORNECEDOR, COMPANY_ADMIN})
    public Map<String, Object> removeImage(@PathVariable String id, @PathVariable String imageId) {
        return catalogService.removeProductImage(id, CurrentUserHolder.get().companyId(), imageId);
    }

    @DeleteMapping("/{id}/documents/{docId}")
    @RequireRole({FORNECEDOR, COMPANY_ADMIN})
    public Map<String, Object> removeDocument(@PathVariable String id, @PathVariable String docId) {
        return catalogService.removeProductDocument(id, CurrentUserHolder.get().companyId(), docId);
    }

    @DeleteMapping("/{id}")
    @RequireRole({FORNECEDOR, COMPANY_ADMIN})
    public ProductDto deactivate(@PathVariable String id, HttpServletRequest req) {
        CurrentUser user = CurrentUserHolder.get();
        ProductDto product = catalogService.deactivateProduct(id, user.companyId());
        auditService.recordSafe(new AuditService.Entry(auditService.actorFrom(user, req), "CATALOGO_PRODUTO_REMOVIDO", "Product",
                product.id(), product.name(), null));
        return product;
    }

    @GetMapping
    public List<ProductDto> list(@RequestParam(required = false) String category,
                                  @RequestParam(required = false) String search,
                                  @RequestParam(required = false) String supplierId,
                                  @RequestParam(required = false) String kind) {
        CurrentUser user = CurrentUserHolder.get();
        // Um comprador não vê (nem compra) produtos da própria empresa.
        String excludeSupplierId = user.role() == PersonaRole.COMPRADOR ? user.companyId() : null;
        return catalogService.listCatalog(new CatalogService.Filtros(category, search, supplierId, excludeSupplierId, kind));
    }

    /** Módulo de Documentação do fornecedor — documentos técnicos dos produtos + de credenciamento da empresa. */
    @GetMapping("/documents")
    @RequireRole({FORNECEDOR, COMPANY_ADMIN})
    public SupplierDocumentsResponse documentosDoFornecedor() {
        return catalogService.listSupplierDocuments(CurrentUserHolder.get().companyId());
    }

    // --- Chaves da API de catálogo (plano Pro) -----------------------------
    // Ficam aqui, ao lado do catálogo, tal como no Node: não é "uma chave da
    // KIXIMA", é uma chave do meu catálogo.

    @GetMapping("/api-keys")
    @RequireRole({FORNECEDOR, COMPANY_ADMIN})
    public List<ApiKeyDto> listarChavesApi() {
        return apiKeyService.listar(CurrentUserHolder.get().companyId());
    }

    @PostMapping("/api-keys")
    @ResponseStatus(CREATED)
    @RequireRole({FORNECEDOR, COMPANY_ADMIN})
    public ApiKeyCreatedDto criarChaveApi(@RequestBody CreateApiKeyRequest body, HttpServletRequest req) {
        CurrentUser user = CurrentUserHolder.get();
        Company empresa = companyRepository.findById(user.companyId()).orElseThrow(() -> new NotFoundException("Empresa"));
        ApiKeyCreatedDto criada = apiKeyService.criar(empresa, body == null ? null : body.nome(), user.id());
        Actor actor = auditService.actorFrom(user, req);
        auditService.recordSafe(new AuditService.Entry(actor, "CHAVE_API_CRIADA", "ApiKey", criada.id(),
                criada.prefixo(), Map.of("nome", criada.nome())));
        return criada;
    }

    @DeleteMapping("/api-keys/{id}")
    @RequireRole({FORNECEDOR, COMPANY_ADMIN})
    public RevokedApiKeyDto revogarChaveApi(@PathVariable String id, HttpServletRequest req) {
        CurrentUser user = CurrentUserHolder.get();
        RevokedApiKeyDto r = apiKeyService.revogar(user.companyId(), id);
        Actor actor = auditService.actorFrom(user, req);
        auditService.recordSafe(new AuditService.Entry(actor, "CHAVE_API_REVOGADA", "ApiKey", id, null, null));
        return r;
    }

    // --- Stock (inventário) -------------------------------------------------
    // Ambas ANTES de /:id, tal como no Node: `/:id` apanharia `/movements`.

    @GetMapping("/movements")
    @RequireRole({FORNECEDOR, COMPANY_ADMIN})
    public PaginaResposta<StockMovementListItemDto> listarMovimentos(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer limit) {
        StockMovementType tipo = type == null || type.isBlank() ? null : tipoValido(type);
        return catalogService.listStockMovements(CurrentUserHolder.get().companyId(), tipo, page, limit);
    }

    @PostMapping("/movements")
    @ResponseStatus(CREATED)
    @RequireRole({FORNECEDOR, COMPANY_ADMIN})
    public StockMovementDto criarMovimento(@RequestBody CreateStockMovementRequest body, HttpServletRequest req) {
        if (body.productId() == null || body.productId().isBlank()) throw new ValidationException("Indique o produto.");
        StockMovementType tipo = tipoValido(body.type());
        if (body.quantity() == null || body.quantity() <= 0) throw new ValidationException("A quantidade tem de ser um número inteiro positivo.");
        CurrentUser user = CurrentUserHolder.get();
        StockMovementDto movimento = catalogService.createStockMovement(user.companyId(), user.id(), body.productId(),
                tipo, body.quantity(), body.note());
        Actor actor = auditService.actorFrom(user, req);
        auditService.recordSafe(new AuditService.Entry(actor, "CATALOGO_MOVIMENTO_CRIADO", "StockMovement", movimento.id(),
                null, Map.of("produtoId", body.productId(), "tipo", tipo.name(), "quantidade", body.quantity())));
        return movimento;
    }

    private StockMovementType tipoValido(String tipo) {
        try {
            return StockMovementType.valueOf(tipo);
        } catch (Exception e) {
            throw new ValidationException("Tipo de movimento inválido — use ENTRADA ou SAIDA.");
        }
    }

    @PatchMapping("/{id}/stock")
    @RequireRole({FORNECEDOR, COMPANY_ADMIN})
    public ProductDto atualizarStock(@PathVariable String id, @RequestBody UpdateStockRequest body, HttpServletRequest req) {
        CurrentUser user = CurrentUserHolder.get();
        ProductDto produto = catalogService.updateStock(id, user.companyId(), body);
        Actor actor = auditService.actorFrom(user, req);
        auditService.recordSafe(new AuditService.Entry(actor, "CATALOGO_STOCK_ATUALIZADO", "Product", produto.id(),
                produto.name(), Map.of("camposAlterados", camposAlterados(body))));
        return produto;
    }

    private List<String> camposAlterados(UpdateStockRequest body) {
        List<String> campos = new java.util.ArrayList<>();
        if (body.stockQuantity() != null) campos.add("stockQuantity");
        if (body.minStock() != null) campos.add("minStock");
        if (body.warehouse() != null) campos.add("warehouse");
        if (body.availability() != null) campos.add("availability");
        return campos;
    }

    @GetMapping("/slug/{slug}")
    public ProductDto getBySlug(@PathVariable String slug) {
        ProductDto product = catalogService.getProductBySlug(slug);
        contarVisualizacaoSeAplicavel(product);
        return product;
    }

    @GetMapping("/{id}")
    public ProductDto getOne(@PathVariable String id) {
        ProductDto product = catalogService.getProduct(id);
        contarVisualizacaoSeAplicavel(product);
        return product;
    }

    /** Conta a visualização quando é um comprador a ver (não o próprio fornecedor) — best-effort, tal como o Node. */
    private void contarVisualizacaoSeAplicavel(ProductDto product) {
        CurrentUser user = CurrentUserHolder.get();
        if (user.role() == PersonaRole.COMPRADOR && !product.supplierId().equals(user.companyId())) {
            catalogService.incrementView(product.id());
        }
    }

    @GetMapping("/{id}/reviews")
    public List<ReviewDto> listReviews(@PathVariable String id) {
        return reviewService.listForProduct(id);
    }

    @PostMapping("/{id}/reviews")
    @ResponseStatus(CREATED)
    @RequireRole({COMPRADOR})
    public ReviewSummaryDto addReview(@PathVariable String id, @RequestBody AddReviewRequest body) {
        if (body.rating() == null || body.rating() < 1 || body.rating() > 5) {
            throw new ValidationException("A avaliação deve ser um número inteiro entre 1 e 5.");
        }
        if (body.comment() != null && body.comment().length() > 1000) {
            throw new ValidationException("O comentário não pode exceder 1000 caracteres.");
        }
        return reviewService.addReview(id, CurrentUserHolder.get().id(), body.rating(), body.comment());
    }
}
