package com.helix.decision.api;

import com.helix.decision.dto.ExecutionDtos.AddSignatoryRequest;
import com.helix.decision.dto.ExecutionDtos.CreatePackageRequest;
import com.helix.decision.dto.ExecutionDtos.DeferRequest;
import com.helix.decision.dto.ExecutionDtos.PackageView;
import com.helix.decision.dto.ExecutionDtos.StatusRequest;
import com.helix.decision.dto.ExecutionDtos.WaiveRequest;
import com.helix.decision.entity.ExecutionPackage;
import com.helix.decision.service.DocumentExecutionService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Document Execution Workflow + Signatory Matrix (CLoM R1-14 / F73-F74). Builds on the
 * existing DocGen / GeneratedDocument + DMS: tracks signing / receipt of a set of generated
 * documents on a deal, with a per-document signatory matrix and deferral / waiver tags. The
 * source GeneratedDocument is never edited here — execution tracks status only.
 */
@RestController
@RequestMapping("/api/execution")
public class DocumentExecutionController {

    private final DocumentExecutionService execution;

    public DocumentExecutionController(DocumentExecutionService execution) {
        this.execution = execution;
    }

    @PostMapping("/packages")
    public PackageView create(@Valid @RequestBody CreatePackageRequest req,
                              @RequestHeader(value = "X-Actor", defaultValue = "cad.officer") String actor) {
        return execution.createPackage(req, actor);
    }

    @GetMapping("/packages")
    public List<ExecutionPackage> list(@RequestParam(required = false) String subjectRef) {
        return execution.list(subjectRef);
    }

    @GetMapping("/packages/{execRef}")
    public PackageView view(@PathVariable String execRef) {
        return execution.view(execRef);
    }

    @PostMapping("/packages/{execRef}/documents/{docId}/signatories")
    public PackageView addSignatory(@PathVariable String execRef, @PathVariable Long docId,
                                    @Valid @RequestBody AddSignatoryRequest req,
                                    @RequestHeader(value = "X-Actor", defaultValue = "cad.officer") String actor) {
        return execution.addSignatory(execRef, docId, req, actor);
    }

    @PostMapping("/packages/{execRef}/documents/{docId}/signatories/{sigId}/sign")
    public PackageView sign(@PathVariable String execRef, @PathVariable Long docId, @PathVariable Long sigId,
                            @RequestHeader(value = "X-Actor", defaultValue = "cad.officer") String actor) {
        return execution.sign(execRef, docId, sigId, actor);
    }

    @PostMapping("/packages/{execRef}/documents/{docId}/status")
    public PackageView setStatus(@PathVariable String execRef, @PathVariable Long docId,
                                 @Valid @RequestBody StatusRequest req,
                                 @RequestHeader(value = "X-Actor", defaultValue = "cad.officer") String actor) {
        return execution.setStatus(execRef, docId, req.status(), actor);
    }

    /**
     * Record receipt of an executed document by UPLOADING the signed file. Stores it in the DMS and
     * marks the document RECEIVED — but only if the signing gates were passed (or the doc is
     * deferred/waived); it cannot be used to skip the execution stepper.
     */
    @PostMapping(value = "/packages/{execRef}/documents/{docId}/receive",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PackageView receive(@PathVariable String execRef, @PathVariable Long docId,
                               @RequestParam("file") MultipartFile file,
                               @RequestHeader(value = "X-Actor", defaultValue = "cad.officer") String actor)
            throws IOException {
        return execution.receiveWithUpload(execRef, docId, file.getOriginalFilename(),
                file.getContentType(), file.getBytes(), actor);
    }

    @PostMapping("/packages/{execRef}/documents/{docId}/defer")
    public PackageView defer(@PathVariable String execRef, @PathVariable Long docId,
                             @Valid @RequestBody DeferRequest req,
                             @RequestHeader(value = "X-Actor", defaultValue = "cad.officer") String actor) {
        return execution.defer(execRef, docId, req.deferralTag(), actor);
    }

    @PostMapping("/packages/{execRef}/documents/{docId}/waive")
    public PackageView waive(@PathVariable String execRef, @PathVariable Long docId,
                             @Valid @RequestBody WaiveRequest req,
                             @RequestHeader(value = "X-Actor", defaultValue = "cad.officer") String actor) {
        return execution.waive(execRef, docId, req.waiverTag(), actor);
    }
}
