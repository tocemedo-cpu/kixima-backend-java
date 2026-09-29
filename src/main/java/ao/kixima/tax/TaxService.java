package ao.kixima.tax;

import ao.kixima.catalog.ProductKind;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Espelha backend/src/services/taxService.js — impostos da lei angolana:
 * IVA 14% sobre tudo, retenção na fonte (II, Lei 26/20) 6,5% só sobre
 * SERVIÇOS, reduzindo o líquido do fornecedor (não soma à fatura). O
 * arredondamento de taxService.js é sempre ao cêntimo mais próximo
 * ({@code Math.round}), nunca por excesso (essa é uma regra à parte, só
 * para o payload AGT — ver faturacaoService.arredondarPorExcessoAoCentimo,
 * M4) — por isso HALF_UP em vez de CEILING aqui.
 */
@Service
public class TaxService {

    private final BigDecimal ivaRate;
    private final BigDecimal withholdingRate;

    public TaxService(@Value("${kixima.tax.iva-rate:0.14}") String ivaRate,
                       @Value("${kixima.tax.withholding-rate:0.065}") String withholdingRate) {
        this.ivaRate = new BigDecimal(ivaRate);
        this.withholdingRate = new BigDecimal(withholdingRate);
    }

    public BigDecimal getIvaRate() {
        return ivaRate;
    }

    private BigDecimal round2(BigDecimal n) {
        return n.setScale(2, RoundingMode.HALF_UP);
    }

    public record ComputedTax(BigDecimal rate, BigDecimal net, BigDecimal tax, BigDecimal gross) {
    }

    public ComputedTax computeTax(BigDecimal netAmount) {
        BigDecimal net = round2(netAmount);
        BigDecimal tax = round2(netAmount.multiply(ivaRate));
        return new ComputedTax(ivaRate, net, tax, round2(netAmount.add(tax)));
    }

    /** Retenção na fonte (II) de uma linha — só se aplica a serviços. */
    public BigDecimal withholdingFor(BigDecimal netAmount, ProductKind kind) {
        return kind == ProductKind.SERVICO ? round2(netAmount.multiply(withholdingRate)) : BigDecimal.ZERO.setScale(2);
    }

    public record Line(BigDecimal net, ProductKind kind) {
    }

    public record Summary(BigDecimal net, BigDecimal tax, BigDecimal gross, BigDecimal withheld,
                           BigDecimal supplierNet, BigDecimal rate, BigDecimal withholdingRate) {
    }

    /**
     * Agrega várias linhas com IVA (14% em tudo) e retenção na fonte (6,5%
     * só nas linhas de serviço). gross = net + IVA (total a pagar pelo
     * comprador); supplierNet = gross - withheld (líquido do fornecedor).
     */
    public Summary summarize(List<Line> lines) {
        BigDecimal net = round2(lines.stream().map(Line::net).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal tax = round2(net.multiply(ivaRate));
        BigDecimal withheld = round2(lines.stream()
                .map(l -> withholdingFor(l.net(), l.kind()))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal gross = round2(net.add(tax));
        return new Summary(net, tax, gross, withheld, round2(gross.subtract(withheld)), ivaRate, withholdingRate);
    }
}
