package com.helix.decision.service;

import com.helix.common.ingest.Canonical;
import com.helix.common.ingest.Ingestion;
import com.helix.common.ingest.SourceSystem;
import com.helix.decision.dto.RepaymentDtos.RawRepaymentEvent;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps a raw servicing-system repayment event onto the canonical
 * {@link Canonical.RepaymentEvent}. Field mapping + validation only; idempotency
 * and persistence are handled by {@link RepaymentService#ingest} per the platform
 * connector contract.
 */
@Component
public class CoreBankingRepaymentConnector implements Ingestion.Connector<RawRepaymentEvent, Canonical.RepaymentEvent> {

    @Override
    public SourceSystem source() {
        return SourceSystem.CORE_BANKING;
    }

    @Override
    public List<String> validate(RawRepaymentEvent raw) {
        List<String> warnings = new ArrayList<>();
        if (raw.externalRef() == null || raw.externalRef().isBlank()) {
            warnings.add("no externalRef on repayment event — traceability to the servicing system is weakened");
        }
        if (raw.principalComponent() != null && raw.interestComponent() != null
                && Math.abs(raw.principalComponent() + raw.interestComponent() - raw.amount()) > 0.01) {
            warnings.add("principal %.2f + interest %.2f != amount %.2f — components renormalised"
                    .formatted(raw.principalComponent(), raw.interestComponent(), raw.amount()));
        }
        if (raw.valueDate() == null || raw.valueDate().isBlank()) {
            warnings.add("no valueDate — defaulted to today");
        }
        return warnings;
    }

    @Override
    public Canonical.RepaymentEvent map(RawRepaymentEvent raw, Ingestion.Provenance provenance) {
        double principal;
        double interest;
        if (raw.principalComponent() == null && raw.interestComponent() == null) {
            principal = raw.amount();
            interest = 0.0;
        } else {
            principal = raw.principalComponent() == null ? 0.0 : raw.principalComponent();
            interest = raw.interestComponent() == null ? 0.0 : raw.interestComponent();
            double sum = principal + interest;
            if (sum > 0 && Math.abs(sum - raw.amount()) > 0.01) {
                // Renormalise to the stated amount, preserving the split ratio.
                principal = raw.amount() * principal / sum;
                interest = raw.amount() - principal;
            }
        }
        LocalDate valueDate = raw.valueDate() == null || raw.valueDate().isBlank()
                ? LocalDate.now() : LocalDate.parse(raw.valueDate());
        return new Canonical.RepaymentEvent(raw.facilityRef(), raw.amount(),
                round2(principal), round2(interest),
                raw.currency() == null ? null : raw.currency().toUpperCase(),
                valueDate, raw.externalRef(), provenance);
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
