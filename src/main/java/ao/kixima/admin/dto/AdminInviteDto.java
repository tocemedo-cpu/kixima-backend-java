package ao.kixima.admin.dto;

import ao.kixima.invite.EmployeeInvite;
import ao.kixima.invite.InviteStatus;

import java.time.Instant;
import java.util.List;

/** adminService.pickAdminInvite — o token NUNCA sai daqui. */
public record AdminInviteDto(String id, String name, String email, List<String> adminAreas, String status,
                             Instant expiresAt, Instant acceptedAt, Instant createdAt) {

    public static AdminInviteDto de(EmployeeInvite i) {
        return new AdminInviteDto(i.getId(), i.getName(), i.getEmail(), i.getAdminAreas(), i.getStatus().name(),
                i.getExpiresAt(), i.getAcceptedAt(), i.getCreatedAt());
    }

    /** applyExpiry — marca como EXPIRADO na leitura os pendentes vencidos, sem escrever na base. */
    public static AdminInviteDto comExpiracao(EmployeeInvite i) {
        boolean expirado = i.getStatus() == InviteStatus.PENDENTE && i.getExpiresAt() != null && i.getExpiresAt().isBefore(Instant.now());
        return new AdminInviteDto(i.getId(), i.getName(), i.getEmail(), i.getAdminAreas(),
                expirado ? InviteStatus.EXPIRADO.name() : i.getStatus().name(), i.getExpiresAt(), i.getAcceptedAt(), i.getCreatedAt());
    }
}
