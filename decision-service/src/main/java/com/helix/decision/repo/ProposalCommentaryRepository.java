package com.helix.decision.repo;

import com.helix.decision.entity.ProposalCommentary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProposalCommentaryRepository extends JpaRepository<ProposalCommentary, Long> {
    List<ProposalCommentary> findByApplicationReferenceOrderByIdDesc(String applicationReference);

    List<ProposalCommentary> findByApplicationReferenceAndSectionOrderByIdDesc(String applicationReference, String section);
}
