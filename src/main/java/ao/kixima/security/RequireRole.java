package ao.kixima.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Espelha `requireRole(...allowedRoles)` em backend/src/middleware/rbac.js —
 * a ação exige que {@link CurrentUser#role()} esteja entre os papéis
 * listados. Ver {@link RbacAspect}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequireRole {
    PersonaRole[] value();
}
