package com.helix.risk.model;

import com.helix.risk.client.RiskMasterClient;
import com.helix.risk.client.RiskMasterClient.MasterRecordDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves a DROPDOWN question's options. Static options come straight off the
 * definition; {@code optionsFromMaster} pulls them from any master type at render
 * time (each ACTIVE record → an option {label, score}), so a bank can govern the
 * option list (e.g. ESG bands) through maker-checker independently of the model.
 */
@Component
public class OptionResolver {

    private final RiskMasterClient masters;

    public OptionResolver(RiskMasterClient masters) {
        this.masters = masters;
    }

    public List<ModelDefs.Option> resolve(ModelDefs.Question q) {
        if (q.optionsFromMaster() != null && !q.optionsFromMaster().isBlank()) {
            List<ModelDefs.Option> out = new ArrayList<>();
            for (MasterRecordDto m : masters.listActive(q.optionsFromMaster())) {
                if (m.payload() == null) continue;
                Object label = m.payload().getOrDefault("label", m.recordKey());
                Object score = m.payload().get("score");
                double s = score instanceof Number n ? n.doubleValue() : 0.0;
                out.add(new ModelDefs.Option(String.valueOf(label), s));
            }
            return out;
        }
        return q.options() == null ? List.of() : q.options();
    }
}
