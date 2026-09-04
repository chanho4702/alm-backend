package com.platform.almbackend.repository;

import com.platform.almbackend.domain.UserPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserPreferenceRepository extends JpaRepository<UserPreference, Long> {
    /** 아바타를 올린 사용자만 — 목록 화면이 한 번에 받아 사용자별 URL을 만든다 */
    List<UserPreference> findByAvatarKeyIsNotNullOrderByUserIdAsc();
}
