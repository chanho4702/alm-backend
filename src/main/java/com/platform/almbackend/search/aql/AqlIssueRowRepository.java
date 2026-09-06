package com.platform.almbackend.search.aql;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** AQL 전용 읽기 리포지토리 — 보관 여부를 술어로 다루려고 {@link AqlIssueRow}를 쓴다 */
public interface AqlIssueRowRepository
        extends JpaRepository<AqlIssueRow, Long>, JpaSpecificationExecutor<AqlIssueRow> {}
