package ao.kixima.security;

/**
 * Equivalente a `req.user` — posto pelo {@link AuthenticationFilter} no
 * início do pedido, lido pelo {@link RbacAspect} e pelos controllers, e
 * sempre limpo no fim do pedido (o filtro corre num `finally`).
 */
public final class CurrentUserHolder {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private CurrentUserHolder() {
    }

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    public static CurrentUser get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
