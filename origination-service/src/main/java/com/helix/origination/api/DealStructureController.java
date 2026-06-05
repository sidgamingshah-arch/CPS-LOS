package com.helix.origination.api;

import com.helix.origination.dto.StructureDtos.AddParticipantRequest;
import com.helix.origination.dto.StructureDtos.SetStructureRequest;
import com.helix.origination.dto.StructureDtos.StructureView;
import com.helix.origination.entity.DealParticipant;
import com.helix.origination.service.DealStructureService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Specialised deal/CP structures — group, joint-obligor, dual-obligor (Islamic),
 * syndication, FI ICR, and renewal/copy.
 */
@RestController
@RequestMapping("/api/applications")
public class DealStructureController {

    private final DealStructureService structures;

    public DealStructureController(DealStructureService structures) {
        this.structures = structures;
    }

    @PostMapping("/{reference}/structure")
    public StructureView setStructure(@PathVariable String reference, @RequestBody SetStructureRequest req,
                                      @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return structures.setStructure(reference, req, actor);
    }

    @GetMapping("/{reference}/structure")
    public StructureView view(@PathVariable String reference) {
        return structures.view(reference);
    }

    @PostMapping("/{reference}/structure/participants")
    public DealParticipant addParticipant(@PathVariable String reference, @RequestBody AddParticipantRequest req,
                                          @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return structures.addParticipant(reference, req, actor);
    }

    @DeleteMapping("/structure/participants/{id}")
    public void removeParticipant(@PathVariable Long id,
                                  @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        structures.removeParticipant(id, actor);
    }

    @PostMapping("/{reference}/structure/copy-from/{sourceReference}")
    public StructureView copyFrom(@PathVariable String reference, @PathVariable String sourceReference,
                                  @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return structures.copyFrom(reference, sourceReference, actor);
    }
}
