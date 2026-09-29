package ao.kixima.security;

import ao.kixima.company.CompanyPlan;
import ao.kixima.company.CompanyType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Espelha o `req.user` populado por backend/src/middleware/auth.js. Posto no
 * {@link CurrentUserHolder} pelo {@link AuthenticationFilter} e lido pelo
 * {@link RbacAspect} e pelos controllers.
 */
public record CurrentUser(
        String id,
        PersonaRole role,
        List<String> adminAreas,
        String companyId,
        CompanyType companyType,
        CompanyPlan companyPlan,
        BigDecimal approvalCap,
        String name,
        String email,
        String avatarUrl,
        boolean mfaPendente,
        boolean mfaRestrita,
        Instant mfaPrazo
) {
}
