package ao.kixima.notification;

import ao.kixima.common.error.NotFoundException;
import ao.kixima.common.pagination.Paginacao;
import ao.kixima.notification.dto.NotificationDto;
import ao.kixima.notification.dto.NotificationListResponse;
import ao.kixima.security.CurrentUser;
import ao.kixima.security.CurrentUserHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Espelha backend/src/controllers/notificationController.js +
 * backend/src/routes/notificationRoutes.js.
 *
 * {@code markRead} NÃO verifica posse antes de actualizar — mesmo
 * comportamento do Node (`prisma.notification.update({ where: { id } })`
 * sem filtrar por userId/companyId); reproduzido tal e qual, não é uma
 * lacuna introduzida aqui.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationRepository notificationRepository;

    public NotificationController(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @GetMapping
    public NotificationListResponse list(@RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer limit) {
        CurrentUser user = CurrentUserHolder.get();
        Pageable pageable = Paginacao.parametros(page, limit, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Notification> pagina = notificationRepository.findByUserIdOrCompanyId(user.id(), user.companyId(), pageable);
        long porLer = notificationRepository.contarPorLer(user.id(), user.companyId());

        return new NotificationListResponse(
                pagina.getContent().stream().map(this::toDto).toList(),
                pagina.getTotalElements(), pagina.getNumber() + 1, pagina.getSize(),
                Math.max(1, pagina.getTotalPages()), porLer);
    }

    @PatchMapping("/{id}/read")
    public NotificationDto markRead(@PathVariable String id) {
        Notification notification = notificationRepository.findById(id).orElseThrow(() -> new NotFoundException("Notificação"));
        notification.setReadAt(Instant.now());
        notificationRepository.save(notification);
        return toDto(notification);
    }

    private NotificationDto toDto(Notification n) {
        return NotificationDto.de(n);
    }
}
