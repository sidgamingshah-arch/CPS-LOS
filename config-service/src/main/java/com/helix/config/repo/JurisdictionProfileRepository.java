package com.helix.config.repo;

import com.helix.config.entity.JurisdictionProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JurisdictionProfileRepository extends JpaRepository<JurisdictionProfile, String> {

    List<JurisdictionProfile> findByActiveTrue();
}
