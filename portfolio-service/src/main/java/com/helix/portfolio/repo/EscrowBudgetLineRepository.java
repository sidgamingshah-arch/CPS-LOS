package com.helix.portfolio.repo;

import com.helix.portfolio.entity.EscrowBudgetLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EscrowBudgetLineRepository extends JpaRepository<EscrowBudgetLine, Long> {

    /** Full history for an account (all versions, all categories), newest first. */
    List<EscrowBudgetLine> findByAccountRefOrderByIdDesc(String accountRef);

    /** The currently active budget lines for an account (one per category). */
    List<EscrowBudgetLine> findByAccountRefAndActiveTrueOrderByCategoryAsc(String accountRef);

    /** The currently active line for a specific category (the supersede pointer). */
    Optional<EscrowBudgetLine> findByAccountRefAndCategoryAndActiveTrue(String accountRef, String category);
}
