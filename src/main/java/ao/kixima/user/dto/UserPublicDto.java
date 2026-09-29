package ao.kixima.user.dto;

import ao.kixima.user.User;

import java.time.Instant;

/** Espelha PUBLIC (userRoutes.js): id, name, email, role, avatarUrl, companyId, createdAt. */
public record UserPublicDto(String id, String name, String email, String role, String avatarUrl, String companyId, Instant createdAt) {

    public static UserPublicDto de(User u) {
        return new UserPublicDto(u.getId(), u.getName(), u.getEmail(), u.getRole().name(), u.getAvatarUrl(), u.getCompanyId(), u.getCreatedAt());
    }
}
