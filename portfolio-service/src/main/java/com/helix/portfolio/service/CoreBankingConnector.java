package com.helix.portfolio.service;

import com.helix.common.ingest.Canonical;
import com.helix.common.ingest.Ingestion.Connector;
import com.helix.common.ingest.Ingestion.Provenance;
import com.helix.common.ingest.SourceSystem;
import com.helix.portfolio.dto.IngestDtos.RawCoreBankingFeed;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Maps a raw core-banking feed onto the canonical facility position. */
@Component
public class CoreBankingConnector implements Connector<RawCoreBankingFeed, Canonical.CoreBankingPosition> {

    @Override
    public SourceSystem source() {
        return SourceSystem.CORE_BANKING;
    }

    @Override
    public List<String> validate(RawCoreBankingFeed raw) {
        List<String> warnings = new ArrayList<>();
        if (raw.outstanding() > raw.sanctionedLimit()) {
            warnings.add("outstanding %.0f exceeds sanctioned limit %.0f — over-limit"
                    .formatted(raw.outstanding(), raw.sanctionedLimit()));
        }
        if (raw.overdueDays() < 0) {
            warnings.add("negative overdueDays");
        }
        return warnings;
    }

    @Override
    public Canonical.CoreBankingPosition map(RawCoreBankingFeed raw, Provenance provenance) {
        double undrawn = Math.max(0, raw.sanctionedLimit() - raw.outstanding());
        String status = raw.accountStatus() == null ? "ACTIVE" : raw.accountStatus().toUpperCase();
        return new Canonical.CoreBankingPosition(raw.facilityRef(), raw.sanctionedLimit(), raw.outstanding(),
                undrawn, raw.currency(), raw.overdueDays(), raw.conductRating(), status, provenance);
    }
}
