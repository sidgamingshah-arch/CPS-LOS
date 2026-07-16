package com.helix.common.crm;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CRM write-back endpoints, automatically present in every service that includes helix-common
 * (like {@code /api/audit} and {@code /api/documents}). Fills the "back-updation to CRM"
 * requirement on the canonical export contract:
 *
 * <ul>
 *   <li>{@code POST /api/crm/writeback} — push a case/decision status back to the CRM. Idempotent
 *       per case/subject + as-of day; re-posting the same day returns the same row.</li>
 *   <li>{@code GET /api/crm/writebacks?subjectRef=} — list write-back rows.</li>
 *   <li>{@code GET /api/crm/writebacks/{id}} — one row with its full canonical envelope.</li>
 * </ul>
 *
 * Default mode is {@code simulated} (records only); {@code helix.crm.mode=live} performs a real POST.
 */
@RestController
@RequestMapping("/api/crm")
public class CrmController {

    private final CrmWriteBackService crm;

    public CrmController(CrmWriteBackService crm) {
        this.crm = crm;
    }

    @PostMapping("/writeback")
    public CrmWriteBack writeBack(@RequestBody CrmWriteBackService.Request req,
                                  @RequestHeader(value = "X-Actor", defaultValue = "crm.writeback") String actor) {
        return crm.writeBack(req, actor);
    }

    @GetMapping("/writebacks")
    public List<CrmWriteBack> list(@RequestParam(required = false) String subjectRef) {
        return crm.list(subjectRef);
    }

    @GetMapping("/writebacks/{id}")
    public CrmWriteBack get(@PathVariable Long id) {
        return crm.get(id);
    }
}
