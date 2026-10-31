package ao.kixima.auth;

import ao.kixima.company.CompanyStatus;
import ao.kixima.company.CompanyType;
import ao.kixima.security.PersonaRole;

import java.util.List;

/** Espelha o objecto `user` devolvido por buildSession() em authService.js. */
public record UserSessionDto(
        String id, String name, String email, PersonaRole role, List<String> adminAreas,
        String companyId, String companyName, CompanyType companyType, String avatarUrl,
        /** Estado do cadastro da empresa (RASCUNHO, PENDENTE, APROVADA…); {@code null} sem empresa. */
        CompanyStatus companyStatus
) {
}
