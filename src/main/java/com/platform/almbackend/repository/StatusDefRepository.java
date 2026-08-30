package com.platform.almbackend.repository;

import com.platform.almbackend.domain.StatusDef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StatusDefRepository extends JpaRepository<StatusDef, String> {
    List<StatusDef> findAllByOrderByIdAsc();
    List<StatusDef> findByCategoryId(String categoryId);
    boolean existsByNameAndIdNot(String name, String id);
    boolean existsByName(String name);
}
