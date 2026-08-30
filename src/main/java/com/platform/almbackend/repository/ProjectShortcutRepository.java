package com.platform.almbackend.repository;

import com.platform.almbackend.domain.ProjectShortcut;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectShortcutRepository extends JpaRepository<ProjectShortcut, Long> {
    List<ProjectShortcut> findByProjectIdOrderBySortOrderAscIdAsc(long projectId);
}
