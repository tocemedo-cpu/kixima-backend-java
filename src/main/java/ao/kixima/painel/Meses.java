package ao.kixima.painel;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Os "últimos 6 meses" das séries dos painéis, com o rótulo `toLocaleDateString('pt-PT', { month: 'short' })` do Node. */
public final class Meses {

    private static final DateTimeFormatter CURTO = DateTimeFormatter.ofPattern("MMM", Locale.forLanguageTag("pt-PT"));
    static final ZoneId ZONA = ZoneId.systemDefault();

    public record Mes(String label, Instant inicio, Instant fim) {
        public boolean contem(Instant t) {
            return t != null && !t.isBefore(inicio) && t.isBefore(fim);
        }
    }

    private Meses() {
    }

    public static List<Mes> ultimosSeis() {
        YearMonth atual = YearMonth.now(ZONA);
        List<Mes> meses = new ArrayList<>();
        for (int i = 5; i >= 0; i--) {
            YearMonth m = atual.minusMonths(i);
            meses.add(new Mes(m.format(CURTO), m.atDay(1).atStartOfDay(ZONA).toInstant(), m.plusMonths(1).atDay(1).atStartOfDay(ZONA).toInstant()));
        }
        return meses;
    }

    public static Instant inicioDoMes() {
        return YearMonth.now(ZONA).atDay(1).atStartOfDay(ZONA).toInstant();
    }

    public static Instant inicioDoAno() {
        return LocalDate.now(ZONA).withDayOfYear(1).atStartOfDay(ZONA).toInstant();
    }
}
