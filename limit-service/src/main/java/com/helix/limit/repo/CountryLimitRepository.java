package com.helix.limit.repo;

import com.helix.limit.entity.CountryLimit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CountryLimitRepository extends JpaRepository<CountryLimit, Long> {
    Optional<CountryLimit> findByCountry(String country);
}
