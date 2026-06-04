package com.helix.limit.api;

import com.helix.limit.entity.CountryLimit;
import com.helix.limit.entity.DepartmentLimit;
import com.helix.limit.entity.FiTransaction;
import com.helix.limit.service.CountryAndFiService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/limits")
public class CountryAndFiController {

    public record CountryRequest(@NotBlank String country, double overallLimit, @NotBlank String currency, String externalRating) {
    }

    public record DepartmentRequest(@NotBlank String country, @NotBlank String department, double limit,
                                    @NotBlank String currency, double cashCollateral) {
    }

    public record FiSubmitRequest(@NotBlank String cif, @NotBlank String country, String department,
                                  @NotBlank String lineId, @NotBlank String facilityType, double amount,
                                  @NotBlank String currency, String productProcessor, String bookingUnit,
                                  String transactionRef, double cashMargin) {
    }

    public record FiDecisionRequest(boolean approve, Double approvedRate, String comment) {
    }

    private final CountryAndFiService countryFi;

    public CountryAndFiController(CountryAndFiService countryFi) {
        this.countryFi = countryFi;
    }

    @PostMapping("/country")
    public CountryLimit upsertCountry(@RequestBody CountryRequest req,
                                      @RequestHeader(value = "X-Actor", defaultValue = "credit.ops") String actor) {
        return countryFi.upsertCountry(req.country(), req.overallLimit(), req.currency(), req.externalRating(), actor);
    }

    @GetMapping("/countries")
    public List<CountryLimit> countries() {
        return countryFi.listCountries();
    }

    @GetMapping("/country/{country}")
    public Map<String, Object> countryView(@PathVariable String country) {
        return countryFi.countryView(country);
    }

    @PostMapping("/department")
    public DepartmentLimit upsertDepartment(@RequestBody DepartmentRequest req,
                                            @RequestHeader(value = "X-Actor", defaultValue = "credit.ops") String actor) {
        return countryFi.upsertDepartment(req.country(), req.department(), req.limit(), req.currency(),
                req.cashCollateral(), actor);
    }

    @PostMapping("/fi/transactions")
    public FiTransaction submitFi(@RequestBody FiSubmitRequest req,
                                  @RequestHeader(value = "X-Actor", defaultValue = "product.processor") String actor) {
        return countryFi.submitFi(req.cif(), req.country(), req.department() == null ? "FI" : req.department(),
                req.lineId(), req.facilityType(), req.amount(), req.currency(), req.productProcessor(),
                req.bookingUnit(), req.transactionRef(), req.cashMargin(), actor);
    }

    @PostMapping("/fi/transactions/{id}/decision")
    public FiTransaction decideFi(@PathVariable Long id, @RequestBody FiDecisionRequest req,
                                  @RequestHeader(value = "X-Actor", defaultValue = "credit.officer") String actor) {
        return countryFi.decideFi(id, req.approve(), req.approvedRate(), req.comment(), actor);
    }

    @GetMapping("/fi/transactions/pending")
    public List<FiTransaction> pendingFi() {
        return countryFi.pendingFi();
    }

    @GetMapping("/fi/transactions/{fid}")
    public FiTransaction fi(@PathVariable String fid) {
        return countryFi.fi(fid);
    }
}
