package com.deni.backend.hazard;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface HazardRepository extends JpaRepository<Hazard, UUID> {

	Optional<Hazard> findByDeviceIdAndSourceEventId(String deviceId, String sourceEventId);

	/** 미해결 상태의 같은 물체 건. 탐지 중복 병합에 사용한다. */
	Optional<Hazard> findFirstByDeviceIdAndChildIdAndObjectTypeAndObjectNameAndStatusOrderByDetectedAtDesc(
			String deviceId, UUID childId, String objectType, String objectName, HazardStatus status);

	List<Hazard> findByDeviceIdAndStatusOrderByDetectedAtDesc(String deviceId, HazardStatus status);

	List<Hazard> findByChildIdAndStatusOrderByDetectedAtDesc(UUID childId, HazardStatus status);

	@Query("""
			SELECT h.objectType AS objectType, h.objectName AS label, h.riskLevel AS riskLevel, COUNT(h) AS count
			FROM Hazard h
			WHERE h.childId = :childId AND h.detectedAt >= :start AND h.detectedAt < :end
			GROUP BY h.objectType, h.objectName, h.riskLevel
			""")
	List<DetectionCount> countDetectionsByObject(@Param("childId") UUID childId,
			@Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);

	interface DetectionCount {
		String getObjectType();
		String getLabel();
		String getRiskLevel();
		long getCount();
	}
}
