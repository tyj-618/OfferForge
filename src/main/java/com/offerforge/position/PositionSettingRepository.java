package com.offerforge.position;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PositionSettingRepository extends JpaRepository<PositionSetting, Long> {

    Optional<PositionSetting> findByUserId(Long userId);
}
