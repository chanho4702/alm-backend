package com.platform.almbackend.repository;

import com.platform.almbackend.domain.IssueTypeDef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IssueTypeDefRepository extends JpaRepository<IssueTypeDef, String> {
    List<IssueTypeDef> findAllByOrderBySortOrderAsc();
    boolean existsByNameAndIdNot(String name, String id);
    boolean existsByName(String name);
}
