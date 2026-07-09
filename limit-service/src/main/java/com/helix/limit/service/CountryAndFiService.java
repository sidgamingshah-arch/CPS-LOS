package com.helix.limit.service;

import com.helix.common.audit.AuditService;
import com.helix.common.money.Money;
import com.helix.common.web.ApiException;
import com.helix.limit.dto.Dtos.UtilisationAction;
import com.helix.limit.dto.Dtos.UtilisationRequest;
import com.helix.limit.dto.Dtos.UtilisationResponse;
import com.helix.limit.entity.CountryLimit;
import com.helix.limit.entity.DepartmentLimit;
import com.helix.limit.entity.FiTransaction;
import com.helix.limit.entity.LimitNode;
import com.helix.limit.repo.CountryLimitRepository;
import com.helix.limit.repo.DepartmentLimitRepository;
import com.helix.limit.repo.FiTransactionRepository;
import com.helix.limit.repo.LimitNodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Country & department limits + FI transaction workflow (PRD Country/Department
 * limit framework and FI transaction processing).
 */
@Service
public class CountryAndFiService {

    private final CountryLimitRepository countries;
    private final DepartmentLimitRepository departments;
    private final FiTransactionRepository fiTx;
    private final LimitNodeRepository nodes;
    private final FxService fx;
    private final UtilisationService utilisations;
    private final AuditService audit;

    public CountryAndFiService(CountryLimitRepository countries, DepartmentLimitRepository departments,
                               FiTransactionRepository fiTx, LimitNodeRepository nodes,
                               FxService fx, UtilisationService utilisations, AuditService audit) {
        this.countries = countries;
        this.departments = departments;
        this.fiTx = fiTx;
        this.nodes = nodes;
        this.fx = fx;
        this.utilisations = utilisations;
        this.audit = audit;
    }

    // -------------------------------------------------------- country / department

    @Transactional
    public CountryLimit upsertCountry(String country, double limit, String currency, String externalRating, String actor) {
        CountryLimit c = countries.findByCountry(country).orElseGet(CountryLimit::new);
        c.setCountry(country);
        c.setOverallLimit(limit);
        c.setCurrency(currency);
        c.setExternalRating(externalRating);
        CountryLimit saved = countries.save(c);
        audit.human(actor, "COUNTRY_LIMIT_UPSERTED", "CountryLimit", country,
                "Country limit %.0f %s, rating %s".formatted(limit, currency, externalRating),
                Map.of("limit", limit, "currency", currency));
        return saved;
    }

    @Transactional
    public DepartmentLimit upsertDepartment(String country, String department, double limit, String currency,
                                            double cashCollateral, String actor) {
        countries.findByCountry(country).orElseThrow(() -> ApiException.notFound("No country limit: " + country));
        double sumDept = departments.findByCountryOrderByDepartmentAsc(country).stream()
                .filter(d -> !d.getDepartment().equalsIgnoreCase(department))
                .mapToDouble(DepartmentLimit::getLimit).sum();
        double countryCap = countries.findByCountry(country).get().getOverallLimit();
        if (sumDept + limit > countryCap + 1e-6) {
            throw ApiException.badRequest("Department total %.0f + new %.0f exceeds country cap %.0f"
                    .formatted(sumDept, limit, countryCap));
        }
        DepartmentLimit d = departments.findByCountryAndDepartment(country, department).orElseGet(DepartmentLimit::new);
        d.setCountry(country);
        d.setDepartment(department.toUpperCase());
        d.setLimit(limit);
        d.setCurrency(currency);
        d.setCashCollateral(cashCollateral);
        DepartmentLimit saved = departments.save(d);
        audit.human(actor, "DEPARTMENT_LIMIT_UPSERTED", "DepartmentLimit", country + ":" + department,
                "Department limit %.0f %s".formatted(limit, currency),
                Map.of("limit", limit, "currency", currency));
        return saved;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> countryView(String country) {
        CountryLimit c = countries.findByCountry(country)
                .orElseThrow(() -> ApiException.notFound("No country limit: " + country));
        List<DepartmentLimit> ds = departments.findByCountryOrderByDepartmentAsc(country);
        double dSum = ds.stream().mapToDouble(DepartmentLimit::getLimit).sum();
        double dOsuc = ds.stream().mapToDouble(DepartmentLimit::getGrossOsuc).sum();
        return Map.of(
                "country", country, "overallLimit", c.getOverallLimit(),
                "currency", c.getCurrency(), "externalRating", c.getExternalRating(),
                "outstanding", c.getOutstanding(), "status", c.getStatus(),
                "departmentsAllocated", dSum,
                "departmentsGrossOsuc", dOsuc,
                "available", Math.max(0, c.getOverallLimit() - c.getOutstanding()),
                "departments", ds);
    }

    @Transactional(readOnly = true)
    public List<CountryLimit> listCountries() {
        return countries.findAll();
    }

    // --------------------------------------------------- FI transaction workflow

    @Transactional
    public FiTransaction submitFi(String cif, String country, String department, String lineId, String facilityType,
                                  double amount, String currency, String productProcessor, String bookingUnit,
                                  String transactionRef, double cashMargin, String actor) {
        // The line must belong to the CIF; capture line for base-currency norms.
        LimitNode n = nodes.findByReference(lineId)
                .orElseThrow(() -> ApiException.notFound("Unknown line: " + lineId));
        if (!cif.equalsIgnoreCase(n.getCif())) {
            throw ApiException.badRequest("Line does not belong to CIF " + cif);
        }
        DepartmentLimit dept = departments.findByCountryAndDepartment(country, department.toUpperCase()).orElse(null);
        double amtBase = fx.toBase(amount, currency);
        boolean breaches = dept != null && (dept.getGrossOsuc() + amtBase > dept.getLimit() + 1e-6);

        FiTransaction tx = new FiTransaction();
        tx.setFid("FID-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        tx.setCif(cif);
        tx.setCountry(country);
        tx.setDepartment(department.toUpperCase());
        tx.setLineId(lineId);
        tx.setFacilityType(facilityType);
        tx.setAmount(amount);
        tx.setCurrency(currency);
        tx.setBaseAmount(amtBase);
        tx.setProductProcessor(productProcessor);
        tx.setBookingUnit(bookingUnit);
        tx.setTransactionRef(transactionRef);
        tx.setCashMargin(cashMargin);
        tx.setBreachesLimit(breaches);
        tx.setStatus("PENDING_APPROVAL");
        tx.setSubmittedBy(actor);
        FiTransaction saved = fiTx.save(tx);
        audit.human(actor, "FI_TX_SUBMITTED", "FiTransaction", saved.getFid(),
                "FI tx %.0f %s on %s%s".formatted(amount, currency, lineId,
                        breaches ? " (BREACHES DEPT LIMIT — exception approval required)" : ""),
                Map.of("fid", saved.getFid(), "breaches", breaches));
        return saved;
    }

    @Transactional
    public FiTransaction decideFi(Long id, boolean approve, Double approvedRate, String comment, String actor) {
        FiTransaction tx = fiTx.findById(id).orElseThrow(() -> ApiException.notFound("No FI tx: " + id));
        if (!"PENDING_APPROVAL".equals(tx.getStatus())) {
            throw ApiException.conflict("FI tx already decided");
        }
        if (tx.getSubmittedBy() != null && tx.getSubmittedBy().equalsIgnoreCase(actor)) {
            throw ApiException.forbiddenAutonomy(
                    "Segregation of duties: FI transaction approver must differ from the submitter (" + tx.getSubmittedBy() + ")");
        }
        tx.setDecidedAt(Instant.now());
        tx.setApprovedBy(actor);
        if (!approve) {
            tx.setStatus("REJECTED");
            tx.setRejectedReason(comment);
            audit.human(actor, "FI_TX_REJECTED", "FiTransaction", tx.getFid(),
                    "Rejected: " + comment, Map.of());
            return fiTx.save(tx);
        }
        tx.setStatus(tx.isBreachesLimit() ? "EXCEPTION_APPROVED" : "APPROVED");
        tx.setApprovedRate(approvedRate);
        // Apply the utilisation against the obligor's limit line (force-override when exception-approved).
        UtilisationResponse u = utilisations.apply(new UtilisationRequest(
                tx.getCif(),
                List.of(new UtilisationAction(tx.getLineId(), "UTILISE", tx.getAmount(), tx.getCurrency(), tx.getFid())),
                tx.getProductProcessor() == null ? "FI-WORKFLOW" : tx.getProductProcessor(),
                tx.isBreachesLimit()), actor);
        // Roll dept gross OSUC if a department limit exists.
        departments.findByCountryAndDepartment(tx.getCountry(), tx.getDepartment()).ifPresent(d -> {
            d.setGrossOsuc(d.getGrossOsuc() + tx.getBaseAmount());
            departments.save(d);
        });
        audit.human(actor, "FI_TX_APPROVED", "FiTransaction", tx.getFid(),
                "Approved %s; utilisation %s".formatted(tx.getStatus(), u.success() ? "OK" : "FAILED"),
                Map.of("status", tx.getStatus(), "utilisationOk", u.success()));
        return fiTx.save(tx);
    }

    @Transactional(readOnly = true)
    public List<FiTransaction> pendingFi() {
        return fiTx.findByStatusOrderBySubmittedAtAsc("PENDING_APPROVAL");
    }

    @Transactional(readOnly = true)
    public FiTransaction fi(String fid) {
        return fiTx.findByFid(fid).orElseThrow(() -> ApiException.notFound("No FI tx: " + fid));
    }
}
