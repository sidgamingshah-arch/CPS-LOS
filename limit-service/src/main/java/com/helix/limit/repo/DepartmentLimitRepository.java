package com.helix.limit.repo;

import com.helix.limit.entity.DepartmentLimit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DepartmentLimitRepository extends JpaRepository<DepartmentLimit, Long> {
    Optional<DepartmentLimit> findByCountryAndDepartment(String country, String department);

    List<DepartmentLimit> findByCountryOrderByDepartmentAsc(String country);
}
