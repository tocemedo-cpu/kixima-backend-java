package ao.kixima.notification.dto;

import ao.kixima.notification.Notification;

import java.time.Instant;

public record NotificationDto(String id, String userId, String companyId, String type, String channel,
                               String title, String message, Instant readAt, String relatedEntityType,
                               String relatedEntityId, Instant createdAt) {

    public static NotificationDto de(Notification n) {
        return new NotificationDto(n.getId(), n.getUserId(), n.getCompanyId(), n.getType().name(), n.getChannel().name(),
                n.getTitle(), n.getMessage(), n.getReadAt(), n.getRelatedEntityType(), n.getRelatedEntityId(), n.getCreatedAt());
    }
}
