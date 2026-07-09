package com.helix.workflow.dto;

import java.util.List;
import java.util.Map;

/** Resolved view of a WORKFLOW_DEFINITION pack — code+version pinned per instance. */
public record WorkflowDefinitionDto(String code, int version, String segment,
                                     List<Map<String, Object>> stages) {
}
