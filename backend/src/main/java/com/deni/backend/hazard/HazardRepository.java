package com.deni.backend.hazard;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface HazardRepository extends JpaRepository<Hazard, UUID> {

	List<Hazard> findByDeviceIdAndStatusOrderByDetectedAtDesc(String deviceId, HazardStatus status);

	List<Hazard> findByChildIdAndStatusOrderByDetectedAtDesc(UUID childId, HazardStatus status);
}
