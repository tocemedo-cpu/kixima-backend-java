package ao.kixima.company.dto;

import ao.kixima.common.Decimais;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import ao.kixima.company.Company;
import ao.kixima.plan.PlanFeatures;
import ao.kixima.plan.PlanService;

import java.math.BigDecimal;

/** Espelha o resumo de subscrição de companyService.subscriptionsFor/subscriptionFor. */
public record SubscriptionDto(CompanyRef company, int activeUsers, PlanService.MonthlyAccessCost monthly, String requiredPlan,
                              PlanFeatures features, @JsonSerialize(using = Decimais.ComoNumeroJs.class) BigDecimal seatPriceCapUsd) {

    /** SUBSCRICAO_SELECT. */
    public record CompanyRef(String id, String name, String type, String size, String plan, BigDecimal seatPriceUsd, Integer employees,
                             BigDecimal annualRevenueUsd, String planNotes) {
        public static CompanyRef de(Company c) {
            return new CompanyRef(c.getId(), c.getName(), c.getType() == null ? null : c.getType().name(),
                    c.getSize() == null ? null : c.getSize().name(), c.getPlan() == null ? null : c.getPlan().name(), c.getSeatPriceUsd(),
                    c.getEmployees(), c.getAnnualRevenueUsd(), c.getPlanNotes());
        }
    }
}
