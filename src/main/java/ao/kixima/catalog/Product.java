package ao.kixima.catalog;

import org.hibernate.annotations.UpdateTimestamp;
import ao.kixima.common.persistence.AbstractPersistableEntity;
import ao.kixima.company.Company;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Espelha o modelo Prisma `Product` (schema.prisma:598-703, tabela
 * `products`). Âmbito deste marco (M2, leitura): todos os campos escalares
 * usados por listCatalog/getProduct/getProductBySlug (catalogService.js).
 * As relações de escrita mais pesadas (poItems, kitItems, quoteItems,
 * poRoboRegras) entram quando os domínios que as escrevem forem portados
 * (M3+) — só `images`/`documents` (necessárias à leitura de um produto) e
 * `supplier` estão mapeadas aqui.
 */
@Entity
@Table(name = "products")
public class Product extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(name = "supplier_id", nullable = false)
    private String supplierId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    private Company supplier;

    @Column(nullable = false)
    private String name;

    private String sku;

    @Column(name = "manufacturer_code")
    private String manufacturerCode;

    @Column(nullable = false)
    private String category;

    private String subcategory;
    private String brand;
    private String manufacturer;
    private String model;

    @Column(name = "country_of_origin")
    private String countryOfOrigin;

    private String description;

    @Column(name = "full_description")
    private String fullDescription;

    private String applications;
    private String benefits;
    private String keywords;

    @Column(name = "unspsc_code")
    private String unspscCode;
    @Column(name = "unspsc_title")
    private String unspscTitle;
    @Column(name = "unspsc_segment")
    private String unspscSegment;
    @Column(name = "unspsc_family")
    private String unspscFamily;
    @Column(name = "unspsc_class")
    private String unspscClass;

    @Column(name = "key_spec")
    private String keySpec;
    private String standard;
    private String warranty;
    private String incoterm;
    @Column(name = "supplier_notes")
    private String supplierNotes;

    private String material;
    private String weight;
    private String height;
    private String width;
    private String length;
    private String pressure;
    private String temperature;
    private String power;
    private String voltage;
    @Column(name = "measurement_unit")
    private String measurementUnit;

    @Column(name = "unit_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "promo_price", precision = 14, scale = 2)
    private BigDecimal promoPrice;

    @Column(nullable = false)
    private String currency = "AOA";

    @Column(name = "min_quantity")
    private Integer minQuantity;
    @Column(name = "max_quantity")
    private Integer maxQuantity;

    @Column(name = "stock_quantity")
    private Integer stockQuantity;
    private String warehouse;
    @Column(name = "lead_time_days")
    private Integer leadTimeDays;
    private String availability;
    @Column(name = "min_stock")
    private Integer minStock;

    private String slug;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private ProductKind kind = ProductKind.PRODUTO;

    private String specialty;
    private String city;
    private String province;
    private String country = "Angola";

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false)
    private List<String> certifications = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false)
    private List<String> tags = new ArrayList<>();

    @Column(nullable = false)
    private boolean active = true;

    private Float rating;

    @Column(name = "review_count", nullable = false)
    private int reviewCount = 0;

    @Column(name = "view_count", nullable = false)
    private int viewCount = 0;

    @Column(name = "search_text")
    private String searchText;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "product", fetch = FetchType.LAZY)
    @OrderBy("primary DESC, sortOrder ASC")
    private List<ProductImage> images = new ArrayList<>();

    @OneToMany(mappedBy = "product", fetch = FetchType.LAZY)
    @OrderBy("type ASC")
    private List<ProductDocument> documents = new ArrayList<>();

    protected Product() {
        // JPA
    }

    /** Criação (catalogService.createProduct / catalogImportService) — os restantes campos entram pelos setters. */
    public Product(String id, String supplierId, String name, String category, BigDecimal unitPrice, String slug, Instant agora) {
        this.id = id;
        this.supplierId = supplierId;
        this.name = name;
        this.category = category;
        this.unitPrice = unitPrice;
        this.slug = slug;
        this.createdAt = agora;
        this.updatedAt = agora;
    }

    /** `updatedAt` é mantido pela aplicação (o Prisma fazia-o com @updatedAt) — chamar depois de qualquer escrita. */
    public void touch() {
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getSupplierId() {
        return supplierId;
    }

    public Company getSupplier() {
        return supplier;
    }

    public String getName() {
        return name;
    }

    public String getSku() {
        return sku;
    }

    public String getManufacturerCode() {
        return manufacturerCode;
    }

    public String getCategory() {
        return category;
    }

    public String getSubcategory() {
        return subcategory;
    }

    public String getBrand() {
        return brand;
    }

    public String getManufacturer() {
        return manufacturer;
    }

    public String getModel() {
        return model;
    }

    public String getCountryOfOrigin() {
        return countryOfOrigin;
    }

    public String getDescription() {
        return description;
    }

    public String getFullDescription() {
        return fullDescription;
    }

    public String getApplications() {
        return applications;
    }

    public String getBenefits() {
        return benefits;
    }

    public String getKeywords() {
        return keywords;
    }

    public String getUnspscCode() {
        return unspscCode;
    }

    public String getUnspscTitle() {
        return unspscTitle;
    }

    public String getUnspscSegment() {
        return unspscSegment;
    }

    public String getUnspscFamily() {
        return unspscFamily;
    }

    public String getUnspscClass() {
        return unspscClass;
    }

    public String getKeySpec() {
        return keySpec;
    }

    public String getStandard() {
        return standard;
    }

    public String getWarranty() {
        return warranty;
    }

    public String getIncoterm() {
        return incoterm;
    }

    public String getSupplierNotes() {
        return supplierNotes;
    }

    public String getMaterial() {
        return material;
    }

    public String getWeight() {
        return weight;
    }

    public String getHeight() {
        return height;
    }

    public String getWidth() {
        return width;
    }

    public String getLength() {
        return length;
    }

    public String getPressure() {
        return pressure;
    }

    public String getTemperature() {
        return temperature;
    }

    public String getPower() {
        return power;
    }

    public String getVoltage() {
        return voltage;
    }

    public String getMeasurementUnit() {
        return measurementUnit;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getPromoPrice() {
        return promoPrice;
    }

    public String getCurrency() {
        return currency;
    }

    public Integer getMinQuantity() {
        return minQuantity;
    }

    public Integer getMaxQuantity() {
        return maxQuantity;
    }

    public Integer getStockQuantity() {
        return stockQuantity;
    }

    public void setStockQuantity(Integer stockQuantity) {
        this.stockQuantity = stockQuantity;
    }

    public String getWarehouse() {
        return warehouse;
    }

    public void setWarehouse(String warehouse) {
        this.warehouse = warehouse;
    }

    public Integer getLeadTimeDays() {
        return leadTimeDays;
    }

    public String getAvailability() {
        return availability;
    }

    public void setAvailability(String availability) {
        this.availability = availability;
    }

    public Integer getMinStock() {
        return minStock;
    }

    public void setMinStock(Integer minStock) {
        this.minStock = minStock;
    }

    public String getSlug() {
        return slug;
    }

    public ProductKind getKind() {
        return kind;
    }

    public String getSpecialty() {
        return specialty;
    }

    public String getCity() {
        return city;
    }

    public String getProvince() {
        return province;
    }

    public String getCountry() {
        return country;
    }

    public List<String> getCertifications() {
        return certifications;
    }

    public List<String> getTags() {
        return tags;
    }

    public boolean isActive() {
        return active;
    }

    public Float getRating() {
        return rating;
    }

    public void setRating(Float rating) {
        this.rating = rating;
    }

    public int getReviewCount() {
        return reviewCount;
    }

    public void setReviewCount(int reviewCount) {
        this.reviewCount = reviewCount;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public int getViewCount() {
        return viewCount;
    }

    public List<ProductImage> getImages() {
        return images;
    }

    public List<ProductDocument> getDocuments() {
        return documents;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    // --- escrita (Lacunas D.2) ---------------------------------------------

    public void setName(String name) {
        this.name = name;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public void setManufacturerCode(String manufacturerCode) {
        this.manufacturerCode = manufacturerCode;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public void setSubcategory(String subcategory) {
        this.subcategory = subcategory;
    }

    public void setBrand(String brand) {
        this.brand = brand;
    }

    public void setManufacturer(String manufacturer) {
        this.manufacturer = manufacturer;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public void setCountryOfOrigin(String countryOfOrigin) {
        this.countryOfOrigin = countryOfOrigin;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setFullDescription(String fullDescription) {
        this.fullDescription = fullDescription;
    }

    public void setApplications(String applications) {
        this.applications = applications;
    }

    public void setBenefits(String benefits) {
        this.benefits = benefits;
    }

    public void setKeywords(String keywords) {
        this.keywords = keywords;
    }

    public void setUnspscCode(String unspscCode) {
        this.unspscCode = unspscCode;
    }

    public void setUnspscTitle(String unspscTitle) {
        this.unspscTitle = unspscTitle;
    }

    public void setUnspscSegment(String unspscSegment) {
        this.unspscSegment = unspscSegment;
    }

    public void setUnspscFamily(String unspscFamily) {
        this.unspscFamily = unspscFamily;
    }

    public void setUnspscClass(String unspscClass) {
        this.unspscClass = unspscClass;
    }

    public void setKeySpec(String keySpec) {
        this.keySpec = keySpec;
    }

    public void setStandard(String standard) {
        this.standard = standard;
    }

    public void setWarranty(String warranty) {
        this.warranty = warranty;
    }

    public void setIncoterm(String incoterm) {
        this.incoterm = incoterm;
    }

    public void setSupplierNotes(String supplierNotes) {
        this.supplierNotes = supplierNotes;
    }

    public void setMaterial(String material) {
        this.material = material;
    }

    public void setWeight(String weight) {
        this.weight = weight;
    }

    public void setHeight(String height) {
        this.height = height;
    }

    public void setWidth(String width) {
        this.width = width;
    }

    public void setLength(String length) {
        this.length = length;
    }

    public void setPressure(String pressure) {
        this.pressure = pressure;
    }

    public void setTemperature(String temperature) {
        this.temperature = temperature;
    }

    public void setPower(String power) {
        this.power = power;
    }

    public void setVoltage(String voltage) {
        this.voltage = voltage;
    }

    public void setMeasurementUnit(String measurementUnit) {
        this.measurementUnit = measurementUnit;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public void setPromoPrice(BigDecimal promoPrice) {
        this.promoPrice = promoPrice;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public void setMinQuantity(Integer minQuantity) {
        this.minQuantity = minQuantity;
    }

    public void setMaxQuantity(Integer maxQuantity) {
        this.maxQuantity = maxQuantity;
    }

    public void setLeadTimeDays(Integer leadTimeDays) {
        this.leadTimeDays = leadTimeDays;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public void setKind(ProductKind kind) {
        this.kind = kind;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public void setProvince(String province) {
        this.province = province;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }
}
