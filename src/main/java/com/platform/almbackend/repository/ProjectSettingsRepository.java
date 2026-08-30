package com.platform.almbackend.repository;

import com.platform.almbackend.domain.ProjectSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectSettingsRepository extends JpaRepository<ProjectSettings, Long> {
    List<ProjectSettings> findBySchemeId(String schemeId);
    long countBySchemeIdAndCustomBodyIsNull(String schemeId);
    boolean existsBySchemeId(String schemeId);
}
