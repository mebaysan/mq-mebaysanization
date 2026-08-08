package com.baysansoft.mqmanager.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectionProfileRepository extends JpaRepository<ConnectionProfile, Long> {

    List<ConnectionProfile> findAllByOrderByNameAsc();

    Optional<ConnectionProfile> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);
}
