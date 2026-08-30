package com.platform.almbackend.repository;

import com.platform.almbackend.domain.SettingsScheme;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SettingsSchemeRepository extends JpaRepository<SettingsScheme, String> {
    List<SettingsScheme> findAllByOrderByIsDefaultDescNameAsc();
    Optional<SettingsScheme> findFirstByIsDefaultTrue();
    boolean existsByName(String name);
}
