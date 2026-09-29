package ao.kixima.po.dto;

import ao.kixima.catalog.dto.ProductDto;
import ao.kixima.po.PurchaseOrderItem;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/** Espelha a linha `PurchaseOrderItem` do Prisma; `product` só quando o `include` o traz (detalhe da PO). */
public record PurchaseOrderItemDto(String id, String purchaseOrderId, String productId, int quantity, BigDecimal unitPrice,
                                   BigDecimal lineTotal,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) ProductDto product) {

    public static PurchaseOrderItemDto de(PurchaseOrderItem i, ProductDto product) {
        return new PurchaseOrderItemDto(i.getId(), i.getPurchaseOrderId(), i.getProductId(), i.getQuantity(), i.getUnitPrice(),
                i.getLineTotal(), product);
    }
}
