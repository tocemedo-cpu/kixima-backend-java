package ao.kixima.catalog;

import ao.kixima.catalog.dto.CompanyDocumentDto;
import ao.kixima.catalog.dto.ProductDocumentDto;
import ao.kixima.catalog.dto.ProductDto;
import ao.kixima.catalog.dto.ProductImageDto;
import ao.kixima.catalog.dto.StockMovementDto;
import ao.kixima.catalog.dto.StockMovementListItemDto;
import ao.kixima.catalog.dto.SupplierDocumentDto;
import ao.kixima.catalog.dto.SupplierDocumentsResponse;
import ao.kixima.catalog.dto.ProductPayload;
import ao.kixima.catalog.dto.UpdateStockRequest;
import ao.kixima.common.error.ForbiddenException;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.common.pagination.PaginaResposta;
import ao.kixima.common.pagination.Paginacao;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyDocument;
import ao.kixima.company.CompanyDocumentRepository;
import ao.kixima.company.CompanyRepository;
import ao.kixima.company.CompanyStatus;
import ao.kixima.company.CompanyType;
import ao.kixima.notification.NotificationService;
import ao.kixima.plan.PlanLimit;
import ao.kixima.plan.PlanService;
import ao.kixima.storage.StorageService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Espelha backend/src/services/catalogService.js. M2: listCatalog,
 * getProduct, getProductBySlug, incrementView (leitura). M5 lote 2d/2f:
 * listSupplierDocuments, updateStock/createStockMovement/listStockMovements
 * ("documentos"/"stock" do plano). Lacunas D.2: createProduct,
 * updateProduct, deactivateProduct, setProductImage, addProductMedia,
 * removeProductImage, removeProductDocument (escrita de catálogo e media).
 */
@Service
public class CatalogService {

    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);

    private final ProductRepository productRepository;
    private final ProductDocumentRepository productDocumentRepository;
    private final CompanyDocumentRepository companyDocumentRepository;
    private final StockMovementRepository stockMovementRepository;
    private final NotificationService notificationService;
    private final ProductImageRepository productImageRepository;
    private final CompanyRepository companyRepository;
    private final PlanService planService;
    private final StorageService storageService;

    @PersistenceContext
    private EntityManager entityManager;

    public CatalogService(ProductRepository productRepository, ProductDocumentRepository productDocumentRepository,
                           CompanyDocumentRepository companyDocumentRepository, StockMovementRepository stockMovementRepository,
                           NotificationService notificationService, ProductImageRepository productImageRepository,
                           CompanyRepository companyRepository, PlanService planService, StorageService storageService) {
        this.productRepository = productRepository;
        this.productDocumentRepository = productDocumentRepository;
        this.companyDocumentRepository = companyDocumentRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.notificationService = notificationService;
        this.productImageRepository = productImageRepository;
        this.companyRepository = companyRepository;
        this.planService = planService;
        this.storageService = storageService;
    }

    // --- Escrita (Lacunas D.2) -------------------------------------------------

    public record Documento(ProductDocType type, MultipartFile file) {
    }

    /** Os ficheiros de uma ficha: capa, galeria e documentos técnicos (por tipo). */
    public record Media(MultipartFile mainImage, List<MultipartFile> gallery, List<Documento> documents) {
        public static Media vazia() {
            return new Media(null, List.of(), List.of());
        }

        List<MultipartFile> galeria() {
            return gallery == null ? List.of() : gallery;
        }

        List<Documento> documentos() {
            return documents == null ? List.of() : documents;
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALFANUMERICO = "abcdefghijklmnopqrstuvwxyz0123456789";

    /** `Math.random().toString(36).slice(2, 8)` — seis caracteres base-36. */
    static String sufixoAleatorio() {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) sb.append(ALFANUMERICO.charAt(RANDOM.nextInt(ALFANUMERICO.length())));
        return sb.toString();
    }

    static String slugify(String name, String hint) {
        String base = Normalizer.normalize(name == null || name.isBlank() ? "item" : name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (base.length() > 60) base = base.substring(0, 60);
        return (base.isEmpty() ? "item" : base) + "-" + hint.substring(0, Math.min(6, hint.length()));
    }

    private static String keyHintDe(String sku, String name) {
        String base = sku != null && !sku.isBlank() ? sku : (name != null && !name.isBlank() ? name : "produto");
        base = base.replaceAll("\\s+", "-");
        return base.length() > 40 ? base.substring(0, 40) : base;
    }

    private static byte[] bytes(MultipartFile f) {
        try {
            return f.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível ler o ficheiro enviado.", e);
        }
    }

    /**
     * A galeria e os documentos deste item cabem no plano? Conta a imagem
     * principal, porque para quem publica ela é uma foto como as outras.
     * `existingImages`/`existingDocs` contam o que o produto já tem.
     */
    private void assertMediaCabeNoPlano(Company empresa, Media media, int existingImages, int existingDocs) {
        int imagens = existingImages + (media.mainImage() != null ? 1 : 0) + media.galeria().size();
        Integer maxImagens = planService.limite(empresa.getPlan(), PlanLimit.IMAGENS_POR_ITEM);
        if (maxImagens != null && imagens > maxImagens) {
            planService.assertLimite(empresa, PlanLimit.IMAGENS_POR_ITEM, maxImagens, "imagens por item");
        }
        int docs = existingDocs + media.documentos().size();
        Integer maxDocs = planService.limite(empresa.getPlan(), PlanLimit.DOCUMENTOS_POR_ITEM);
        if (maxDocs != null && docs > maxDocs) {
            planService.assertLimite(empresa, PlanLimit.DOCUMENTOS_POR_ITEM, maxDocs, "documentos técnicos por item");
        }
    }

    private Product produtoDaEmpresa(String id, String supplierCompanyId, String mensagem) {
        Product product = productRepository.findById(id).orElseThrow(() -> new NotFoundException("Produto"));
        if (!product.getSupplierId().equals(supplierCompanyId)) throw new ForbiddenException(mensagem);
        return product;
    }

    /** Recarrega as colecções `images`/`documents` depois de escrever nelas por repositório. */
    private void recarregar(Product product) {
        entityManager.flush();
        entityManager.refresh(product);
    }

    /** Espelha catalogService.createProduct — bytes primeiro (storage), linhas depois. */
    @Transactional
    public ProductDto createProduct(String supplierCompanyId, ProductPayload data, Media media) {
        Company supplier = companyRepository.findById(supplierCompanyId).orElse(null);
        if (supplier == null || supplier.getType() != CompanyType.FORNECEDOR) {
            throw new ForbiddenException("Apenas empresas fornecedoras podem publicar itens no catálogo.");
        }
        // Quantos itens a empresa publica NÃO é limitado, em plano nenhum. O que o plano limita é quanta MÍDIA cada item leva.
        assertMediaCabeNoPlano(supplier, media, 0, 0);
        if (supplier.getStatus() != CompanyStatus.APROVADA) {
            throw new ForbiddenException("A empresa precisa estar credenciada (due diligence aprovada) para publicar itens.");
        }

        String keyHint = keyHintDe(data.texto("sku"), data.texto("name"));
        Instant agora = Instant.now();
        Product product = new Product(UUID.randomUUID().toString(), supplierCompanyId, data.texto("name"), data.texto("category"),
                data.unitPrice(), slugify(data.texto("name"), sufixoAleatorio()), agora);
        data.aplicar(product);

        // Imagem: a carregada pelo fornecedor tem prioridade; senão, a imagem do catálogo de referência (data.imageUrl).
        List<ProductImage> imagens = new ArrayList<>();
        String primaryUrl = null;
        if (media.mainImage() != null) {
            MultipartFile m = media.mainImage();
            primaryUrl = storageService.saveFile(bytes(m), m.getOriginalFilename(), m.getContentType(), keyHint + "-main");
            imagens.add(new ProductImage(UUID.randomUUID().toString(), product.getId(), primaryUrl, true, 0, agora));
        }
        List<MultipartFile> galeria = media.galeria();
        for (int i = 0; i < galeria.size(); i++) {
            MultipartFile g = galeria.get(i);
            String url = storageService.saveFile(bytes(g), g.getOriginalFilename(), g.getContentType(), keyHint + "-g" + i);
            // Se não houver imagem principal, a primeira da galeria assume esse papel.
            if (primaryUrl == null && i == 0) {
                primaryUrl = url;
                imagens.add(new ProductImage(UUID.randomUUID().toString(), product.getId(), url, true, 0, agora));
            } else {
                imagens.add(new ProductImage(UUID.randomUUID().toString(), product.getId(), url, false, i + 1, agora));
            }
        }
        List<ProductDocument> docs = new ArrayList<>();
        for (Documento d : media.documentos()) {
            MultipartFile f = d.file();
            String fileUrl = storageService.saveFile(bytes(f), f.getOriginalFilename(), f.getContentType(), keyHint + "-" + d.type().name(), "documents");
            docs.add(new ProductDocument(UUID.randomUUID().toString(), product.getId(), d.type(), fileUrl, f.getOriginalFilename(), agora));
        }
        if (primaryUrl != null) product.setImageUrl(primaryUrl);

        productRepository.save(product);
        productImageRepository.saveAll(imagens);
        productDocumentRepository.saveAll(docs);
        recarregar(product);
        return toDto(product, null, true);
    }

    /** Espelha catalogService.updateProduct — só os campos enviados; devolve o produto sem `images`/`documents`, como o `prisma.product.update` sem include. */
    @Transactional
    public ProductDto updateProduct(String id, String supplierCompanyId, ProductPayload data) {
        Product product = produtoDaEmpresa(id, supplierCompanyId, "Só pode editar itens da sua própria empresa.");
        data.aplicar(product);
        return toDto(product, null, false);
    }

    @Transactional
    public ProductDto deactivateProduct(String id, String supplierCompanyId) {
        Product product = produtoDaEmpresa(id, supplierCompanyId, "Só pode remover itens da sua própria empresa.");
        product.setActive(false);
        product.touch();
        return toDto(product, null, false);
    }

    /**
     * Espelha catalogService.setProductImage — mantém a galeria em sincronia
     * com Product.imageUrl: a foto anterior não é apagada, só deixa de ser a
     * principal e passa a fazer parte da galeria.
     */
    @Transactional
    public ProductDto setProductImage(String id, String supplierCompanyId, MultipartFile file) {
        Product product = produtoDaEmpresa(id, supplierCompanyId, "Só pode editar itens da sua própria empresa.");
        // Guarda no provider configurado só depois de validar a propriedade.
        String imageUrl = storageService.saveFile(bytes(file), file.getOriginalFilename(), file.getContentType(), id);
        List<ProductImage> imagens = productImageRepository.findByProductIdOrderByPrimaryDescSortOrderAsc(id);
        int nextSortOrder = imagens.stream().mapToInt(ProductImage::getSortOrder).max().orElse(-1) + 1;
        for (ProductImage img : imagens) if (img.isPrimary()) img.setPrimary(false);
        productImageRepository.save(new ProductImage(UUID.randomUUID().toString(), id, imageUrl, true, nextSortOrder, Instant.now()));
        product.setImageUrl(imageUrl);
        product.touch();
        recarregar(product);
        return toDto(product, null, false);
    }

    /** Espelha catalogService.addProductMedia — acrescenta fotos/documentos a um item publicado, contando o que já tem para o limite do plano. */
    @Transactional
    public ProductDto addProductMedia(String id, String supplierCompanyId, Media media) {
        Product product = produtoDaEmpresa(id, supplierCompanyId, "Só pode editar itens da sua própria empresa.");
        Company supplier = companyRepository.findById(supplierCompanyId).orElseThrow(() -> new NotFoundException("Empresa"));
        List<ProductImage> existentes = productImageRepository.findByProductIdOrderByPrimaryDescSortOrderAsc(id);
        List<ProductDocument> docsExistentes = productDocumentRepository.findByProductIdOrderByTypeAsc(id);
        Media semCapa = new Media(null, media.galeria(), media.documentos());
        assertMediaCabeNoPlano(supplier, semCapa, existentes.size(), docsExistentes.size());

        String keyHint = keyHintDe(product.getSku(), product.getName());
        boolean hasPrimaryAlready = existentes.stream().anyMatch(ProductImage::isPrimary);
        int nextSortOrder = existentes.stream().mapToInt(ProductImage::getSortOrder).max().orElse(-1) + 1;
        Instant agora = Instant.now();

        List<ProductImage> imagens = new ArrayList<>();
        List<MultipartFile> galeria = media.galeria();
        for (int i = 0; i < galeria.size(); i++) {
            MultipartFile g = galeria.get(i);
            String url = storageService.saveFile(bytes(g), g.getOriginalFilename(), g.getContentType(), keyHint + "-add" + i);
            // Só vira principal se o produto ainda não tinha nenhuma foto.
            imagens.add(new ProductImage(UUID.randomUUID().toString(), id, url, !hasPrimaryAlready && i == 0, nextSortOrder++, agora));
        }
        List<ProductDocument> docs = new ArrayList<>();
        for (Documento d : media.documentos()) {
            MultipartFile f = d.file();
            String fileUrl = storageService.saveFile(bytes(f), f.getOriginalFilename(), f.getContentType(), keyHint + "-" + d.type().name() + "-add", "documents");
            docs.add(new ProductDocument(UUID.randomUUID().toString(), id, d.type(), fileUrl, f.getOriginalFilename(), agora));
        }
        productImageRepository.saveAll(imagens);
        productDocumentRepository.saveAll(docs);
        imagens.stream().filter(ProductImage::isPrimary).findFirst().ifPresent(nova -> product.setImageUrl(nova.getUrl()));
        product.touch();
        recarregar(product);
        return toDto(product, null, true);
    }

    /** Remove uma foto da galeria. Se era a principal, promove a próxima (menor sortOrder) e sincroniza Product.imageUrl. */
    @Transactional
    public Map<String, Object> removeProductImage(String productId, String supplierCompanyId, String imageId) {
        Product product = produtoDaEmpresa(productId, supplierCompanyId, "Só pode editar itens da sua própria empresa.");
        List<ProductImage> imagens = productImageRepository.findByProductIdOrderByPrimaryDescSortOrderAsc(productId);
        ProductImage image = imagens.stream().filter(i -> i.getId().equals(imageId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Imagem"));
        productImageRepository.delete(image);
        if (image.isPrimary()) {
            ProductImage proxima = imagens.stream().filter(i -> !i.getId().equals(imageId)).findFirst().orElse(null);
            if (proxima != null) proxima.setPrimary(true);
            product.setImageUrl(proxima == null ? null : proxima.getUrl());
            product.touch();
        }
        recarregar(product);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", imageId);
        m.put("removida", true);
        return m;
    }

    /** Remove um documento técnico. Não apaga o ficheiro do storage — mesmo princípio de "não apagar" de deactivateProduct. */
    @Transactional
    public Map<String, Object> removeProductDocument(String productId, String supplierCompanyId, String docId) {
        Product product = produtoDaEmpresa(productId, supplierCompanyId, "Só pode editar itens da sua própria empresa.");
        ProductDocument doc = productDocumentRepository.findByProductIdOrderByTypeAsc(productId).stream()
                .filter(d -> d.getId().equals(docId)).findFirst().orElseThrow(() -> new NotFoundException("Documento"));
        productDocumentRepository.delete(doc);
        recarregar(product);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", docId);
        m.put("removido", true);
        return m;
    }

    public record Filtros(String category, String search, String supplierId, String excludeSupplierId, String kind) {
    }

    /**
     * Sem paginação de resposta — o Node também não pagina esta lista. O
     * tecto de segurança de config/database.js (DB_MAX_ROWS) aplica-se aqui
     * como em qualquer outra leitura em lista, pelo mecanismo transversal
     * de {@link ao.kixima.common.persistence.TectoDeLinhas}: limite alto que
     * nunca deveria ser atingido em uso normal e, se for, um erro no log
     * (o Node também só regista no log — não reporta ao Sentry).
     */
    @Transactional(readOnly = true)
    public List<ProductDto> listCatalog(Filtros filtros) {
        ProductKind kind = "PRODUTO".equals(filtros.kind()) || "SERVICO".equals(filtros.kind())
                ? ProductKind.valueOf(filtros.kind()) : null;
        List<Product> produtos = productRepository.findAll(
                ProductSpecifications.comFiltros(filtros.category(), kind, filtros.supplierId(),
                        filtros.excludeSupplierId(), filtros.search()),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return produtos.stream().map(p -> toDto(p, supplierListView(p.getSupplier()), false)).toList();
    }

    @Transactional(readOnly = true)
    public ProductDto getProduct(String id) {
        Product product = productRepository.findById(id).orElseThrow(() -> new NotFoundException("Produto"));
        return toDto(product, supplierMinView(product.getSupplier()), true);
    }

    @Transactional(readOnly = true)
    public ProductDto getProductBySlug(String slug) {
        Product product = productRepository.findBySlug(slug).orElseThrow(() -> new NotFoundException("Produto"));
        return toDto(product, supplierSlugView(product.getSupplier()), true);
    }

    /**
     * Espelha catalogService.listSupplierDocuments — documentos técnicos
     * de todos os produtos do fornecedor (ficha técnica, certificado,
     * catálogo, ...) + os documentos de credenciamento da própria empresa
     * (Alvará, Licença ANPG, Certidão Comercial), para o módulo de
     * Documentação.
     */
    @Transactional(readOnly = true)
    public SupplierDocumentsResponse listSupplierDocuments(String supplierCompanyId) {
        List<SupplierDocumentDto> productDocs = productDocumentRepository.findBySupplierIdOrderByCreatedAtDesc(supplierCompanyId)
                .stream()
                .map(d -> new SupplierDocumentDto(d.getId(), d.getType().name(), d.getFileUrl(), d.getOriginalName(),
                        d.getCreatedAt(), d.getProductId(), d.getProduct() == null || d.getProduct().getName() == null
                        ? "—" : d.getProduct().getName()))
                .toList();
        List<CompanyDocumentDto> companyDocs = companyDocumentRepository.findByCompanyIdOrderByTypeAsc(supplierCompanyId)
                .stream()
                .map(this::toDto)
                .toList();
        return new SupplierDocumentsResponse(productDocs, companyDocs);
    }

    private CompanyDocumentDto toDto(CompanyDocument d) {
        return new CompanyDocumentDto(d.getId(), d.getType().name(), d.getFileUrl(), d.getOriginalName(), d.getCreatedAt());
    }

    /**
     * Espelha catalogService.updateStock — só os campos de inventário.
     * Um campo ausente no pedido não altera o valor já guardado (ver
     * {@link UpdateStockRequest}).
     */
    @Transactional
    public ProductDto updateStock(String id, String supplierCompanyId, UpdateStockRequest body) {
        Product product = productRepository.findById(id).orElseThrow(() -> new NotFoundException("Produto"));
        if (!product.getSupplierId().equals(supplierCompanyId)) {
            throw new ForbiddenException("Só pode gerir o stock de itens da sua própria empresa.");
        }
        int stockAnterior = product.getStockQuantity() == null ? 0 : product.getStockQuantity();
        Integer minStockAnterior = product.getMinStock();

        if (body.stockQuantity() != null) product.setStockQuantity(body.stockQuantity());
        if (body.minStock() != null) product.setMinStock(body.minStock());
        if (body.warehouse() != null) product.setWarehouse(body.warehouse());
        if (body.availability() != null) product.setAvailability(body.availability());

        // Aviso de stock baixo só na TRANSIÇÃO — ver createStockMovement.
        boolean estavaAbaixo = minStockAnterior != null && stockAnterior <= minStockAnterior;
        boolean estaAbaixoAgora = product.getMinStock() != null
                && (product.getStockQuantity() == null ? 0 : product.getStockQuantity()) <= product.getMinStock();
        if (!estavaAbaixo && estaAbaixoAgora) {
            notificationService.estoqueBaixo(product.getSupplierId(), product.getId(), product.getName(),
                    product.getStockQuantity(), product.getMinStock());
        }
        return toDto(product, null, false);
    }

    /** Espelha catalogService.createStockMovement — regista a entrada/saída e ajusta o stock do produto. */
    @Transactional
    public StockMovementDto createStockMovement(String supplierCompanyId, String userId, String productId,
                                                 StockMovementType type, int quantity, String note) {
        Product product = productRepository.findById(productId).orElse(null);
        if (product == null || !product.getSupplierId().equals(supplierCompanyId)) {
            throw new NotFoundException("Produto");
        }
        int current = product.getStockQuantity() == null ? 0 : product.getStockQuantity();
        int delta = type == StockMovementType.ENTRADA ? quantity : -quantity;
        int next = Math.max(0, current + delta);
        product.setStockQuantity(next);

        StockMovement movement = new StockMovement(UUID.randomUUID().toString(), productId, type, quantity,
                note == null || note.isBlank() ? null : note, userId, Instant.now());
        stockMovementRepository.save(movement);

        // Aviso de stock baixo só na TRANSIÇÃO, para não repetir a cada movimento seguinte.
        if (product.getMinStock() != null && current > product.getMinStock() && next <= product.getMinStock()) {
            notificationService.estoqueBaixo(product.getSupplierId(), product.getId(), product.getName(), next, product.getMinStock());
        }
        return new StockMovementDto(movement.getId(), movement.getProductId(), movement.getType().name(),
                movement.getQuantity(), movement.getNote(), movement.getCreatedById(), movement.getCreatedAt());
    }

    /** Espelha catalogService.listStockMovements — histórico paginado, o mais recente primeiro. */
    @Transactional(readOnly = true)
    public PaginaResposta<StockMovementListItemDto> listStockMovements(String supplierCompanyId, StockMovementType type,
                                                                         Integer page, Integer limit) {
        Pageable pageable = Paginacao.parametros(page, limit);
        Page<StockMovement> pagina = type != null
                ? stockMovementRepository.findByProduct_SupplierIdAndTypeOrderByCreatedAtDesc(supplierCompanyId, type, pageable)
                : stockMovementRepository.findByProduct_SupplierIdOrderByCreatedAtDesc(supplierCompanyId, pageable);
        return Paginacao.envelope(pagina.map(this::toDto));
    }

    private StockMovementListItemDto toDto(StockMovement m) {
        String nomeProduto = m.getProduct() == null ? null : m.getProduct().getName();
        return new StockMovementListItemDto(m.getId(), m.getType().name(), m.getQuantity(), m.getNote(),
                m.getCreatedAt(), m.getProductId(), new StockMovementListItemDto.ProductNameRef(nomeProduto));
    }

    /** Best-effort, tal como incrementView() no Node — nunca bloqueia a resposta do chamador. */
    @Transactional
    public void incrementView(String productId) {
        try {
            productRepository.incrementViewCount(productId);
        } catch (Exception e) {
            log.debug("incrementView: produto inexistente ou falha best-effort ({})", productId);
        }
    }

    private Map<String, Object> supplierListView(Company c) {
        if (c == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("status", c.getStatus());
        m.put("verified", c.isVerified());
        m.put("logoUrl", c.getLogoUrl());
        m.put("city", c.getCity());
        m.put("country", c.getCountry());
        return m;
    }

    private Map<String, Object> supplierMinView(Company c) {
        if (c == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("status", c.getStatus());
        return m;
    }

    private Map<String, Object> supplierSlugView(Company c) {
        if (c == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("status", c.getStatus());
        m.put("verified", c.isVerified());
        m.put("logoUrl", c.getLogoUrl());
        return m;
    }

    /** O `Product` tal como o Prisma o devolve (todas as colunas) — usado também pela pesquisa do marketplace. */
    public ProductDto toDto(Product p, Map<String, Object> supplier, boolean incluirMedia) {
        return new ProductDto(
                p.getId(), p.getSupplierId(), supplier,
                p.getName(), p.getSku(), p.getManufacturerCode(), p.getCategory(), p.getSubcategory(),
                p.getBrand(), p.getManufacturer(), p.getModel(), p.getCountryOfOrigin(),
                p.getDescription(), p.getFullDescription(), p.getApplications(), p.getBenefits(), p.getKeywords(),
                p.getUnspscCode(), p.getUnspscTitle(), p.getUnspscSegment(), p.getUnspscFamily(), p.getUnspscClass(),
                p.getKeySpec(), p.getStandard(), p.getWarranty(), p.getIncoterm(), p.getSupplierNotes(),
                p.getMaterial(), p.getWeight(), p.getHeight(), p.getWidth(), p.getLength(),
                p.getPressure(), p.getTemperature(), p.getPower(), p.getVoltage(), p.getMeasurementUnit(),
                p.getUnitPrice(), p.getPromoPrice(), p.getCurrency(), p.getMinQuantity(), p.getMaxQuantity(),
                p.getStockQuantity(), p.getWarehouse(), p.getLeadTimeDays(), p.getAvailability(), p.getMinStock(),
                p.getSlug(), p.getKind().name(), p.getSpecialty(), p.getCity(), p.getProvince(), p.getCountry(),
                p.getCertifications(), p.getTags(), p.isActive(),
                p.getRating(), p.getReviewCount(), p.getViewCount(), p.getImageUrl(),
                p.getCreatedAt(), p.getUpdatedAt(),
                incluirMedia ? p.getImages().stream().map(i -> new ProductImageDto(i.getId(), i.getUrl(), i.isPrimary(), i.getSortOrder())).toList() : null,
                incluirMedia ? p.getDocuments().stream().map(d -> new ProductDocumentDto(d.getId(), d.getType().name(), d.getFileUrl(), d.getOriginalName())).toList() : null
        );
    }
}
