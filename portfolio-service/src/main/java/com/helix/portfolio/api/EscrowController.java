package com.helix.portfolio.api;

import com.helix.portfolio.dto.EscrowDtos.AccountView;
import com.helix.portfolio.dto.EscrowDtos.BudgetLineRequest;
import com.helix.portfolio.dto.EscrowDtos.BudgetVsActual;
import com.helix.portfolio.dto.EscrowDtos.CreateAccountRequest;
import com.helix.portfolio.dto.EscrowDtos.TransactionRequest;
import com.helix.portfolio.entity.EscrowAccount;
import com.helix.portfolio.entity.EscrowBudgetLine;
import com.helix.portfolio.entity.EscrowTransaction;
import com.helix.portfolio.service.EscrowService;
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
 * Escrow monitoring — a record / monitoring surface (post-disbursement). Accounts host
 * append-only versioned budget lines and category-tagged transactions; the budget-vs-actual
 * read is deterministic with a RAG band from the {@code VALIDATION_PARAMETER} thresholds.
 * It NEVER mutates an authoritative figure (ECL / IRAC / exposure / limit). Every write
 * takes {@code X-Actor}; the gateway routes {@code /portfolio/api/escrow/...} here.
 */
@RestController
@RequestMapping("/api/escrow")
public class EscrowController {

    private final EscrowService service;

    public EscrowController(EscrowService service) {
        this.service = service;
    }

    @PostMapping("/accounts")
    public EscrowAccount create(@RequestBody CreateAccountRequest req,
                                @RequestHeader(value = "X-Actor", defaultValue = "portfolio.manager") String actor) {
        return service.createAccount(req, actor);
    }

    @GetMapping("/accounts")
    public List<EscrowAccount> list(@RequestParam(required = false) String subjectRef) {
        return service.list(subjectRef);
    }

    @GetMapping("/accounts/{ref}")
    public AccountView get(@PathVariable String ref) {
        return service.view(ref);
    }

    @PostMapping("/accounts/{ref}/budget-lines")
    public EscrowBudgetLine addBudgetLine(@PathVariable String ref, @RequestBody BudgetLineRequest req,
                                          @RequestHeader(value = "X-Actor", defaultValue = "portfolio.manager") String actor) {
        return service.addBudgetLine(ref, req, actor);
    }

    @GetMapping("/accounts/{ref}/budget-lines")
    public List<EscrowBudgetLine> budgetHistory(@PathVariable String ref) {
        return service.budgetHistory(ref);
    }

    @PostMapping("/accounts/{ref}/transactions")
    public EscrowTransaction postTransaction(@PathVariable String ref, @RequestBody TransactionRequest req,
                                             @RequestHeader(value = "X-Actor", defaultValue = "portfolio.manager") String actor) {
        return service.postTransaction(ref, req, actor);
    }

    @GetMapping("/accounts/{ref}/transactions")
    public List<EscrowTransaction> transactions(@PathVariable String ref) {
        return service.transactions(ref);
    }

    @GetMapping("/accounts/{ref}/budget-vs-actual")
    public BudgetVsActual budgetVsActual(@PathVariable String ref) {
        return service.budgetVsActual(ref);
    }
}
