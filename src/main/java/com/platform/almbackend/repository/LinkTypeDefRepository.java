package com.platform.almbackend.repository;

import com.platform.almbackend.domain.LinkTypeDef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LinkTypeDefRepository extends JpaRepository<LinkTypeDef, String> {
    List<LinkTypeDef> findAllByOrderBySortOrderAsc();
    boolean existsByNameAndIdNot(String name, String id);
    boolean existsByName(String name);
}
