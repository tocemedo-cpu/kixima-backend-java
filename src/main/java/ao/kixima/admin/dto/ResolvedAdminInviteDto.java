package ao.kixima.admin.dto;

import java.util.List;

/** adminService.resolveAdminInvite — só para leitura, mostra as áreas a quem abre o link. */
public record ResolvedAdminInviteDto(String name, String email, List<String> adminAreas) {
}
