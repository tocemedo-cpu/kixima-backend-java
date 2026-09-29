package ao.kixima.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Espelha `requirePermission(area)` em rbac.js — restringe uma rota já
 * aberta a ADMIN_SISTEMA a uma área específica de {@link AdminArea}. Usar
 * SEMPRE a seguir a {@code @RequireRole} (nunca sozinho): é o
 * {@code @RequireRole} que garante que só ADMIN_SISTEMA (e o mais que a
 * rota permitir) chega aqui. NÃO afeta outras personas. `adminAreas` VAZIO
 * no utilizador continua a significar Super Admin — acede a tudo (ver
 * {@link RbacAspect}).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequirePermission {
    /** Uma constante de {@link AdminArea}, ex.: {@code AdminArea.FINANCEIRO}. */
    String value();
}
