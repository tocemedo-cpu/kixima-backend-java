package ao.kixima.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Espelha o objecto `Product` tal como o Prisma o devolve (todas as colunas
 * escalares) mais as relações que cada endpoint inclui. `supplier` varia de
 * forma (campos diferentes) consoante o endpoint — listCatalog inclui
 * {id,name,status,verified,logoUrl,city,country}, getProduct só
 * {id,name,status}, getProductBySlug {id,name,status,verified,logoUrl} — por
 * isso viaja como Map em vez de um tipo fixo, para reproduzir exactamente as
 * chaves que o Node devolve em cada caso (`select` do Prisma omite chaves,
 * não as deixa null). `images`/`documents` ficam de fora (null → omitidos)
 * na listagem, tal como o Node. Só estas três relações são condicionais: os
 * escalares da linha saem sempre, {@code null} incluído ({@code sku: null},
 * {@code promoPrice: null}…), como o Prisma os devolve.
 */
public record ProductDto(
        String id, String supplierId, @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> supplier,
        String name, String sku, String manufacturerCode, String category, String subcategory,
        String brand, String manufacturer, String model, String countryOfOrigin,
        String description, String fullDescription, String applications, String benefits, String keywords,
        String unspscCode, String unspscTitle, String unspscSegment, String unspscFamily, String unspscClass,
        String keySpec, String standard, String warranty, String incoterm, String supplierNotes,
        String material, String weight, String height, String width, String length,
        String pressure, String temperature, String power, String voltage, String measurementUnit,
        BigDecimal unitPrice, BigDecimal promoPrice, String currency, Integer minQuantity, Integer maxQuantity,
        Integer stockQuantity, String warehouse, Integer leadTimeDays, String availability, Integer minStock,
        String slug, String kind, String specialty, String city, String province, String country,
        List<String> certifications, List<String> tags, boolean active,
        Float rating, int reviewCount, int viewCount, String imageUrl,
        Instant createdAt, Instant updatedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<ProductImageDto> images,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<ProductDocumentDto> documents
) {
}
