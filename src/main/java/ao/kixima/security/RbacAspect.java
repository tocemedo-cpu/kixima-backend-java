package ao.kixima.security;

import ao.kixima.common.error.ForbiddenException;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Espelha backend/src/middleware/rbac.js — intercepta métodos de controller
 * anotados com {@link RequireRole}/{@link RequirePermission}/
 * {@link RequireSuperAdmin} e aplica as mesmas regras, pela mesma ordem em
 * que o Node compõe os middlewares (requireRole primeiro, depois
 * requirePermission, depois requireSuperAdmin — os três podem coexistir no
 * mesmo método, tal como coexistem na mesma rota no Node).
 *
 * IMPORTANTE (paridade exacta com o Node): {@code @RequirePermission}
 * sozinho NÃO bloqueia outras personas — `requirePermission()` no rbac.js
 * devolve `next()` imediatamente para quem não é ADMIN_SISTEMA. É por isso
 * que o comentário do Node diz "usar sempre depois de requireRole" — aqui
 * reproduz-se o mesmo comportamento pass-through, não uma versão "mais
 * segura" que mudaria o contrato.
 */
@Aspect
@Component
public class RbacAspect {

    @Before("execution(* ao.kixima..*Controller.*(..))")
    public void aplicarRegras(JoinPoint jp) {
        Method method = ((MethodSignature) jp.getSignature()).getMethod();

        RequireRole requireRole = method.getAnnotation(RequireRole.class);
        if (requireRole != null) {
            aplicarRequireRole(requireRole);
        }

        RequirePermission requirePermission = method.getAnnotation(RequirePermission.class);
        if (requirePermission != null) {
            aplicarRequirePermission(requirePermission);
        }

        RequireSuperAdmin requireSuperAdmin = method.getAnnotation(RequireSuperAdmin.class);
        if (requireSuperAdmin != null) {
            aplicarRequireSuperAdmin();
        }
    }

    private void aplicarRequireRole(RequireRole anno) {
        CurrentUser user = CurrentUserHolder.get();
        if (user == null) {
            throw new ForbiddenException();
        }
        List<PersonaRole> permitidos = Arrays.asList(anno.value());
        if (!permitidos.contains(user.role())) {
            String lista = permitidos.stream().map(Enum::name).collect(Collectors.joining(", "));
            throw new ForbiddenException("Esta ação requer um dos seguintes perfis: " + lista + ".");
        }
    }

    private void aplicarRequirePermission(RequirePermission anno) {
        CurrentUser user = CurrentUserHolder.get();
        if (user == null || user.role() != PersonaRole.ADMIN_SISTEMA) return; // pass-through, tal como o Node.
        List<String> areas = user.adminAreas();
        if (areas == null || areas.isEmpty() || areas.contains(anno.value())) return;
        String rotulo = AdminArea.AREAS_ADMIN_LABEL.getOrDefault(anno.value(), anno.value());
        throw new ForbiddenException(
                "Esta ação está fora das suas áreas. Precisa de \"" + rotulo + "\" — fale com quem lhe deu acesso ao sistema.");
    }

    private void aplicarRequireSuperAdmin() {
        CurrentUser user = CurrentUserHolder.get();
        boolean temAreas = user != null && user.adminAreas() != null && !user.adminAreas().isEmpty();
        if (user == null || user.role() != PersonaRole.ADMIN_SISTEMA || temAreas) {
            throw new ForbiddenException("Esta ação está reservada ao Super Admin.");
        }
    }
}
