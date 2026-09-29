package ao.kixima.quote.dto;

import ao.kixima.catalog.Product;
import ao.kixima.company.Company;
import ao.kixima.quote.QuoteItem;
import ao.kixima.quote.QuoteRequest;
import ao.kixima.quote.QuoteStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Espelha o QuoteRequest com o INCLUDE de quoteService.js (items.product, buyerCompany, supplierCompany). */
public record QuoteDto(String id, String buyerCompanyId, String supplierCompanyId, String createdById, QuoteStatus status, String note,
                       BigDecimal responsePrice, Integer responseLeadDays, String responseNote, Instant respondedAt,
                       Instant createdAt, Instant updatedAt, List<ItemDto> items, CompanyRef buyerCompany, CompanyRef supplierCompany) {

    public record CompanyRef(String id, String name) {
        public static CompanyRef de(Company c) {
            return c == null ? null : new CompanyRef(c.getId(), c.getName());
        }
    }

    public record ProductRef(String id, String name, BigDecimal unitPrice, String currency) {
        public static ProductRef de(Product p) {
            return p == null ? null : new ProductRef(p.getId(), p.getName(), p.getUnitPrice(), p.getCurrency());
        }
    }

    public record ItemDto(String id, String quoteRequestId, String productId, int quantity, ProductRef product) {
        public static ItemDto de(QuoteItem i) {
            return new ItemDto(i.getId(), i.getQuoteRequestId(), i.getProductId(), i.getQuantity(), ProductRef.de(i.getProduct()));
        }
    }

    public static QuoteDto de(QuoteRequest q, List<QuoteItem> items) {
        return new QuoteDto(q.getId(), q.getBuyerCompanyId(), q.getSupplierCompanyId(), q.getCreatedById(), q.getStatus(), q.getNote(),
                q.getResponsePrice(), q.getResponseLeadDays(), q.getResponseNote(), q.getRespondedAt(), q.getCreatedAt(), q.getUpdatedAt(),
                items.stream().map(ItemDto::de).toList(), CompanyRef.de(q.getBuyerCompany()), CompanyRef.de(q.getSupplierCompany()));
    }
}
