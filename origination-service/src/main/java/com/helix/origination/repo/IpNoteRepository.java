package com.helix.origination.repo;

import com.helix.origination.entity.IpNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IpNoteRepository extends JpaRepository<IpNote, Long> {

    Optional<IpNote> findByIpNoteRef(String ipNoteRef);

    boolean existsByIpNoteRef(String ipNoteRef);

    List<IpNote> findAllByOrderByCreatedAtDesc();

    List<IpNote> findByCounterpartyRefOrderByCreatedAtDesc(String counterpartyRef);

    List<IpNote> findByStatusOrderByCreatedAtDesc(IpNote.Status status);
}
