package ao.kixima.admin.dto;

import java.time.Instant;
import java.util.List;

/** adminService.listUsers — a linha de cada utilizador da plataforma, com o nome da empresa resolvido. */
public record AdminUserDto(String id, String name, String email, String role, boolean active, String companyName,
                           Instant createdAt, List<String> adminAreas) {
}
