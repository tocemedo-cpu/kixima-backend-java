package ao.kixima.po.dto;

import java.util.List;

/**
 * Envelope de `listPurchaseOrders` quando chamada com `page` (poService.js:266)
 * — nomes de campo em inglês, diferentes do envelope genérico de
 * {@link ao.kixima.common.pagination.PaginaResposta} (itens/pagina/porPagina):
 * esta função do Node nunca passou pelo utils/paginacao.js partilhado, construiu
 * o seu próprio objecto, e o frontend (pages/fornecedor/Invoices.jsx) lê
 * exactamente estes nomes.
 */
public record PurchaseOrderPageDto(List<PurchaseOrderDto> items, long total, int page, int pages, int limit) {
}
