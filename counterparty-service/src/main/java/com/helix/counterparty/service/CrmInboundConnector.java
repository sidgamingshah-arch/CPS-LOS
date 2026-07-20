package com.helix.counterparty.service;

import com.helix.common.ingest.Canonical;
import com.helix.common.ingest.Ingestion.Connector;
import com.helix.common.ingest.Ingestion.Provenance;
import com.helix.common.ingest.SourceSystem;
import com.helix.counterparty.dto.IngestDtos.RawCrmPayload;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps a raw inbound CRM vendor payload onto the canonical {@link Canonical.CrmProfile}
 * (PRD §8). Named {@code CrmInboundConnector} to avoid clashing with the helix-common
 * OUTBOUND {@code com.helix.common.crm.CrmConnector} (case-status write-back). The CRM
 * profile is ingested as INPUT/provenance data only — it never mutates an authoritative figure.
 */
@Component
public class CrmInboundConnector implements Connector<RawCrmPayload, Canonical.CrmProfile> {

    @Override
    public SourceSystem source() {
        return SourceSystem.CRM;
    }

    @Override
    public List<String> validate(RawCrmPayload raw) {
        List<String> warnings = new ArrayList<>();
        if (raw.crmId() == null || raw.crmId().isBlank()) {
            warnings.add("payload missing crmId");
        }
        if (raw.accountName() == null || raw.accountName().isBlank()) {
            warnings.add("payload missing accountName");
        }
        if (raw.relationshipManager() == null || raw.relationshipManager().isBlank()) {
            warnings.add("payload missing relationshipManager");
        }
        if (raw.segment() == null || raw.segment().isBlank()) {
            warnings.add("payload missing segment");
        }
        return warnings;
    }

    @Override
    public Canonical.CrmProfile map(RawCrmPayload raw, Provenance provenance) {
        return new Canonical.CrmProfile(
                raw.crmId(),
                raw.accountName(),
                raw.relationshipManager(),
                raw.segment(),
                raw.relationshipValue() == null ? 0.0 : raw.relationshipValue(),
                raw.primaryContactName(),
                raw.primaryContactEmail(),
                raw.productsHeld() == null ? List.of() : raw.productsHeld(),
                raw.lifecycleStage(),
                provenance);
    }
}
