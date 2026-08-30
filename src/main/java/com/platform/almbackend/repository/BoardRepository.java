package com.platform.almbackend.repository;

import com.platform.almbackend.domain.Board;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BoardRepository extends JpaRepository<Board, Long> {
    List<Board> findByProjectIdOrderByIsDefaultDescCreatedAtAscIdAsc(long projectId);
    long countByProjectId(long projectId);
}
