package ao.kixima.security;

import java.time.Duration;

/**
 * Interpreta strings de duração no mesmo formato aceite pelo `jsonwebtoken`
 * do Node (biblioteca `ms`): um número simples em segundos, ou um número
 * seguido de `d`/`h`/`m`/`s`. Usado para `JWT_EXPIRES_IN` e os TTLs fixos de
 * authService.js (convites 7d, reset de senha 1h, desafio 2FA 15m).
 */
final class JwtDuration {

    private JwtDuration() {
    }

    static Duration parse(String value) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) return Duration.ofDays(1);
        try {
            if (v.endsWith("d")) return Duration.ofDays(Long.parseLong(v.substring(0, v.length() - 1)));
            if (v.endsWith("h")) return Duration.ofHours(Long.parseLong(v.substring(0, v.length() - 1)));
            if (v.endsWith("m")) return Duration.ofMinutes(Long.parseLong(v.substring(0, v.length() - 1)));
            if (v.endsWith("s")) return Duration.ofSeconds(Long.parseLong(v.substring(0, v.length() - 1)));
            return Duration.ofSeconds(Long.parseLong(v));
        } catch (NumberFormatException e) {
            return Duration.ofDays(1);
        }
    }
}
