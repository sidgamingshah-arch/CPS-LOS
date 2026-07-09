package com.helix.origination.repo;

import com.helix.origination.entity.SyndicationInvitation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SyndicationInvitationRepository extends JpaRepository<SyndicationInvitation, Long> {
    List<SyndicationInvitation> findByApplicationReferenceOrderByIdDesc(String applicationReference);
}
