package com.platform.almbackend.repository;

import com.platform.almbackend.domain.StatusCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StatusCategoryRepository extends JpaRepository<StatusCategory, String> {
    List<StatusCategory> findAllByOrderBySortOrderAsc();
    boolean existsByNameAndIdNot(String name, String id);
    boolean existsByName(String name);
}
