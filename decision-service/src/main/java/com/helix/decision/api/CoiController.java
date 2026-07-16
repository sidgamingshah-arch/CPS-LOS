package com.helix.decision.api;

import com.helix.decision.dto.CoiDtos.AttestRequest;
import com.helix.decision.entity.CoiAttestation;
import com.helix.decision.service.CoiService;
import jakarta.validation.Valid;
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
 * Conflict-of-interest (COI) attestation API. An actor records their own attestation
 * for a subject (identity comes from X-Actor, not the body); attestations are listed
 * per subject. When a jurisdiction's DOA_MATRIX pack sets {@code require_coi_attestation},
 * the decision workflow consults these records to gate a decision/committee-vote.
 */
@RestController
@RequestMapping("/api/coi")
public class CoiController {

    private final CoiService coi;

    public CoiController(CoiService coi) {
        this.coi = coi;
    }

    /** Record the acting human's COI attestation for a subject. */
    @PostMapping
    public CoiAttestation attest(@Valid @RequestBody AttestRequest req,
                                 @RequestHeader(value = "X-Actor", defaultValue = "credit.officer") String actor) {
        return coi.attest(req.subjectType(), req.subjectRef(), req.role(), req.declaration(), req.note(), actor);
    }

    /** All attestations for a subject (newest first). */
    @GetMapping
    public List<CoiAttestation> list(@RequestParam String subjectRef) {
        return coi.listBySubject(subjectRef);
    }

    @GetMapping("/{coiRef}")
    public CoiAttestation get(@PathVariable String coiRef) {
        return coi.get(coiRef);
    }
}
