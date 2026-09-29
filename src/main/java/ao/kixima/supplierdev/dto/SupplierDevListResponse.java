package ao.kixima.supplierdev.dto;

import java.util.List;

public record SupplierDevListResponse(List<SupplierDevRequestDto> items, long total, int page, int pages,
                                       SupplierDevKpisDto kpis) {
}
