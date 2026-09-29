package ao.kixima.audit;

/** Espelha o objecto devolvido por actorFrom()/anonimoFrom() em auditService.js. */
public record Actor(String actorId, String actorName, String actorRole, String companyId, String ip) {

    public static Actor anonimo(String ip) {
        return new Actor(null, null, null, null, ip);
    }
}
