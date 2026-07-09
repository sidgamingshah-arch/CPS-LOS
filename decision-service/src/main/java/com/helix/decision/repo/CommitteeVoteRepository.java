package com.helix.decision.repo;

import com.helix.decision.entity.CommitteeVote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommitteeVoteRepository extends JpaRepository<CommitteeVote, Long> {
    List<CommitteeVote> findByDecisionIdOrderByCastAtAsc(Long decisionId);

    long countByDecisionId(Long decisionId);
}
