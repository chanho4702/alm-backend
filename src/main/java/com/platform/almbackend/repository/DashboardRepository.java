package com.platform.almbackend.repository;

import com.platform.almbackend.domain.Dashboard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DashboardRepository extends JpaRepository<Dashboard, Long> {
    List<Dashboard> findByOwnerIdOrSharedTrueOrderByCreatedAtAscIdAsc(long ownerId);
}
