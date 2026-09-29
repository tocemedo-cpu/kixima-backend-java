package ao.kixima.realtime;

import ao.kixima.security.CurrentUser;

import java.security.Principal;

/**
 * Espelha {@code socket.user} de realtimeService.js — o utilizador autenticado
 * no CONNECT, preso à sessão STOMP. {@link #getName()} é o id do utilizador:
 * é por ele que o Spring resolve os destinos {@code /user/...} (a sala
 * {@code user:<id>} do Socket.IO).
 */
public record RealtimePrincipal(CurrentUser user) implements Principal {

    @Override
    public String getName() {
        return user.id();
    }
}
