package ao.kixima.admin.dto;

import java.util.List;

/** Corpos de adminRoutes.js — createAdminInviteSchema / acceptAdminInviteSchema / status / areas. */
public final class AdminRequests {

    private AdminRequests() {
    }

    public record CreateAdminInvite(String name, String email, List<String> adminAreas) {
    }

    /** `adminAreas` no corpo é IGNORADO de propósito (o schema despe-o antes de chegar ao serviço). */
    public record AcceptAdminInvite(String password, Boolean termsAccepted) {
    }

    public record SetStatus(Boolean active) {
    }

    public record SetAreas(List<String> areas) {
    }
}
