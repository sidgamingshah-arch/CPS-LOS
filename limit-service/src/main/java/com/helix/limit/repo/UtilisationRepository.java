package com.helix.limit.repo;

import com.helix.limit.entity.Utilisation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UtilisationRepository extends JpaRepository<Utilisation, Long> {
    List<Utilisation> findByLimitNodeIdOrderByCreatedAtDesc(Long limitNodeId);

    List<Utilisation> findByCifOrderByCreatedAtDesc(String cif);

    /** Idempotency lookup — a repeated UTILISE/RESERVE on the same txn ref returns the original row. */
    Optional<Utilisation> findFirstByTransactionRefAndActionAndStatus(
            String transactionRef, String action, String status);

    /** G4 override review queue — confirmed bookings where an override was exercised. */
    List<Utilisation> findByOverrideAppliedTrueOrderByCreatedAtDesc();

    List<Utilisation> findByCifAndOverrideAppliedTrueOrderByCreatedAtDesc(String cif);
}
