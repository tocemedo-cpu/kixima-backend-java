package ao.kixima.common.money;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Espelha {@code fxRate}/{@code toUsd} de backend/src/services/platformFeeService.js
 * — o câmbio USD→AOA configurável ({@code KIXIMA_USD_AOA_RATE}) com que a
 * plataforma afere limiares definidos em dólares (o Kwanza flutua). Só a
 * conversão: a taxa da plataforma em si continua fora deste marco.
 */
@Service
public class FxService {

    private final BigDecimal usdAoaRate;

    public FxService(@Value("${kixima.business.usd-aoa-rate:900}") BigDecimal usdAoaRate) {
        this.usdAoaRate = usdAoaRate;
    }

    public BigDecimal fxRate() {
        return usdAoaRate;
    }

    /** Converte um valor da moeda da transação para USD, a 2 casas (round2 do Node). */
    public BigDecimal toUsd(BigDecimal amount, String currency) {
        BigDecimal v = amount == null ? BigDecimal.ZERO : amount;
        if (currency != null && "USD".equalsIgnoreCase(currency)) return v.setScale(2, RoundingMode.HALF_UP);
        return v.divide(usdAoaRate, 2, RoundingMode.HALF_UP);
    }
}
