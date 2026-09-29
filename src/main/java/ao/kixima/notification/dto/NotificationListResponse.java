package ao.kixima.notification.dto;

import java.util.List;

/** `{ ...envelope(itens, total, p), porLer }` em notificationController.js:list(). */
public record NotificationListResponse(List<NotificationDto> itens, long total, int pagina, int porPagina,
                                        int paginas, long porLer) {
}
