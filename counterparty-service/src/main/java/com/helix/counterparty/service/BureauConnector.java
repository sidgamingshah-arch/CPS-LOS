package com.helix.counterparty.service;

import com.helix.common.ingest.Canonical;
import com.helix.common.ingest.Ingestion.Connector;
import com.helix.common.ingest.Ingestion.Provenance;
import com.helix.common.ingest.SourceSystem;
import com.helix.counterparty.dto.IngestDtos.RawBureauPayload;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps a raw credit-bureau vendor payload onto the canonical {@link Canonical.BureauReport}
 * (PRD §8). The bureau score is ingested as INPUT/provenance data only — it never creates or
 * mutates a Rating or any authoritative figure. Missing optional fields surface as warnings.
 */
@Component
public class BureauConnector implements Connector<RawBureauPayload, Canonical.BureauReport> {

    @Override
    public SourceSystem source() {
        return SourceSystem.CREDIT_BUREAU;
    }

    @Override
    public List<String> validate(RawBureauPayload raw) {
        List<String> warnings = new ArrayList<>();
        if (raw.subjectName() == null || raw.subjectName().isBlank()) {
            warnings.add("payload missing subjectName");
        }
        if (raw.score() == null) {
            warnings.add("payload missing credit score");
        } else if (raw.score() < 0 || raw.score() > 900) {
            warnings.add("credit score %d outside typical [0,900] range".formatted(raw.score()));
        }
        if (raw.scoreModel() == null || raw.scoreModel().isBlank()) {
            warnings.add("payload missing scoreModel");
        }
        if (raw.oldestAcctMonths() == null) {
            warnings.add("payload missing oldestAcctMonths");
        }
        return warnings;
    }

    @Override
    public Canonical.BureauReport map(RawBureauPayload raw, Provenance provenance) {
        return new Canonical.BureauReport(
                raw.subjectName(),
                raw.subjectId(),
                raw.score(),
                raw.scoreModel(),
                raw.enquiries6m() == null ? 0 : raw.enquiries6m(),
                raw.delinquencies24m() == null ? 0 : raw.delinquencies24m(),
                raw.tradelines() == null ? 0 : raw.tradelines(),
                raw.outstanding() == null ? 0.0 : raw.outstanding(),
                raw.oldestAcctMonths(),
                provenance);
    }
}
