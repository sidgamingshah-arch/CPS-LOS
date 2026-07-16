package com.helix.common.crm;

import com.helix.common.export.Export;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default {@link CrmConnector}: records only (no network egress). The durable, examiner-visible
 * {@code crm_write_back} row is the deliverable — mirroring the downstream export façade, which
 * persists an idempotent {@code ExportBatch} rather than calling out. Active whenever
 * {@code helix.crm.mode} is absent or {@code simulated}, so a fresh install never egresses.
 */
@Component
@ConditionalOnProperty(name = "helix.crm.mode", havingValue = "simulated", matchIfMissing = true)
public class SimulatedCrmConnector implements CrmConnector {

    @Override
    public String mode() {
        return "SIMULATED";
    }

    @Override
    public Result push(Export.Envelope<Export.CrmCaseStatusRecord> envelope) {
        return Result.recorded();
    }
}
