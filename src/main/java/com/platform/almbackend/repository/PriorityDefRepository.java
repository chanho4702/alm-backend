package com.platform.almbackend.repository;

import com.platform.almbackend.domain.PriorityDef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PriorityDefRepository extends JpaRepository<PriorityDef, String> {
    List<PriorityDef> findAllByOrderBySortOrderAsc();
    boolean existsByNameAndIdNot(String name, String id);
    boolean existsByName(String name);
}
