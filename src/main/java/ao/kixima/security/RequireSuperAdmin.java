package ao.kixima.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Espelha `requireSuperAdmin()` em rbac.js — ações NUNCA delegáveis a um
 * assessor com área restrita: gerir quem tem acesso ao sistema e a que
 * áreas. Exige {@code role == ADMIN_SISTEMA} E {@code adminAreas} vazio.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequireSuperAdmin {
}
