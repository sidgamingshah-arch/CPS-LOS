package com.helix.counterparty.service;

import com.helix.common.ingest.Canonical;
import com.helix.common.ingest.Ingestion.Connector;
import com.helix.common.ingest.Ingestion.Provenance;
import com.helix.common.ingest.SourceSystem;
import com.helix.counterparty.dto.IngestDtos.RawScreeningPayload;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Maps a raw sanctions/screening vendor payload onto the canonical screening result. */
@Component
public class ScreeningConnector implements Connector<RawScreeningPayload, Canonical.ScreeningResult> {

    @Override
    public SourceSystem source() {
        return SourceSystem.SANCTIONS_SCREENING;
    }

    @Override
    public List<String> validate(RawScreeningPayload raw) {
        List<String> warnings = new ArrayList<>();
        if (raw.entityName() == null || raw.entityName().isBlank()) {
            warnings.add("payload missing entityName");
        }
        if (raw.matches() != null) {
            for (var m : raw.matches()) {
                if (m.score() < 0 || m.score() > 1) {
                    warnings.add("match '%s' score %.2f outside [0,1]".formatted(m.name(), m.score()));
                }
            }
        }
        return warnings;
    }

    @Override
    public Canonical.ScreeningResult map(RawScreeningPayload raw, Provenance provenance) {
        List<Canonical.ScreeningResult.Hit> hits = new ArrayList<>();
        if (raw.matches() != null) {
            for (var m : raw.matches()) {
                hits.add(new Canonical.ScreeningResult.Hit(
                        m.list(), m.name(), m.score(), normaliseSeverity(m.risk()),
                        m.fields() == null ? List.of() : m.fields()));
            }
        }
        return new Canonical.ScreeningResult(raw.entityName(), hits, provenance);
    }

    private String normaliseSeverity(String vendorRisk) {
        return switch (vendorRisk == null ? "" : vendorRisk.toUpperCase(Locale.ROOT)) {
            case "CRITICAL", "SEVERE" -> "SEVERE";
            case "HIGH" -> "HIGH";
            case "MEDIUM", "MED", "MODERATE" -> "MEDIUM";
            default -> "LOW";
        };
    }
}
