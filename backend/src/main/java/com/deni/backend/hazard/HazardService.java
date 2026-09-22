package com.deni.backend.hazard;

import com.deni.backend.common.ApiException;
import com.deni.backend.common.IdempotencyGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class HazardService {

	private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

	private final HazardRepository hazardRepository;
	private final IdempotencyGuard idempotencyGuard;

	public HazardService(HazardRepository hazardRepository, IdempotencyGuard idempotencyGuard) {
		this.hazardRepository = hazardRepository;
		this.idempotencyGuard = idempotencyGuard;
	}

	@Transactional(readOnly = true)
	public List<HazardListItem> findHazards(String deviceId, HazardStatus status) {
		String normalizedDeviceId = requireText(deviceId, "deviceId", 100);
		return hazardRepository.findByDeviceIdAndStatusOrderByDetectedAtDesc(normalizedDeviceId, status).stream()
				.map(this::toListItem)
				.toList();
	}

	/**
	 * 대시보드에서 아이 기준으로 진행 중인 위험 건을 조회한다. 기기 등록 전에도 사용할 수 있도록
	 * deviceId가 아닌 childId를 기준으로 하며, 내부 열거형을 외부에 노출하지 않는다.
	 */
	@Transactional(readOnly = true)
	public List<ActiveHazardSummary> findActiveHazardsForChild(UUID childId) {
		if (childId == null) {
			throw ApiException.validation("아이 ID를 입력해 주세요.", Map.of("childId", "아이 ID는 필수입니다."));
		}
		return hazardRepository.findByChildIdAndStatusOrderByDetectedAtDesc(childId, HazardStatus.ACTIVE).stream()
				.map(hazard -> new ActiveHazardSummary(hazard.getId(), hazard.getObjectName(),
						hazard.getRiskLevel().name(), hazard.getLocationLabel(), hazard.getDetectedAt()))
				.toList();
	}

	@Transactional(readOnly = true)
	public HazardDetailResult getHazard(UUID hazardId) {
		Hazard hazard = hazardRepository.findById(hazardId)
				.orElseThrow(() -> ApiException.notFound("HAZARD_NOT_FOUND", "위험 감지 정보를 찾을 수 없습니다."));
		return toDetail(hazard);
	}

	@Transactional(readOnly = true)
	public List<ObjectDetectionCount> getMonthlyDetections(UUID childId, OffsetDateTime start,
			OffsetDateTime end) {
		Map<ObjectKey, ObjectDetectionCount> counts = new HashMap<>();
		for (HazardRepository.DetectionCount row : hazardRepository.countDetectionsByObject(childId, start, end)) {
			ObjectKey key = new ObjectKey(row.getObjectType(), row.getLabel());
			ObjectDetectionCount existing = counts.get(key);
			RiskLevel highestRisk = RiskLevel.valueOf(row.getRiskLevel());
			if (existing != null) {
				RiskLevel previousRisk = RiskLevel.valueOf(existing.riskLevel());
				if (previousRisk.ordinal() < highestRisk.ordinal()) highestRisk = previousRisk;
			}
			long count = row.getCount() + (existing == null ? 0 : existing.count());
			counts.put(key, new ObjectDetectionCount(key.objectType(), key.label(), count, highestRisk.name()));
		}
		return counts.values().stream()
				.sorted(Comparator.comparingLong(ObjectDetectionCount::count).reversed()
						.thenComparing(ObjectDetectionCount::objectType).thenComparing(ObjectDetectionCount::label))
				.toList();
	}

	@Transactional(readOnly = true)
	public void requireActiveAssociation(UUID hazardId, UUID childId, String deviceId) {
		Hazard hazard = hazardRepository.findById(hazardId)
				.orElseThrow(() -> ApiException.notFound("HAZARD_NOT_FOUND", "위험 감지 정보를 찾을 수 없습니다."));
		if (!hazard.getChildId().equals(childId) || !hazard.getDeviceId().equals(deviceId)) {
			throw ApiException.conflict("HAZARD_DEVICE_MISMATCH", "위험 건의 아이와 등록 기기의 연결 정보가 일치하지 않습니다.");
		}
		if (hazard.getStatus() != HazardStatus.ACTIVE) {
			throw ApiException.conflict("HAZARD_ALREADY_RESOLVED", "이미 해결된 위험 건은 재확인을 요청할 수 없습니다.");
		}
	}

	@Transactional
	public HazardDetailResult recordDetection(DetectionInput input) {
		if (input == null) {
			throw ApiException.validation("탐지 데이터를 입력해 주세요.", Map.of("detection", "필수입니다."));
		}
		if (input.childId() == null) {
			throw ApiException.validation("아이 ID를 입력해 주세요.", Map.of("childId", "아이 ID는 필수입니다."));
		}
		String deviceId = requireText(input.deviceId(), "deviceId", 100);
		String eventId = requireText(input.eventId(), "eventId", 100);
		String objectType = requireText(input.objectType(), "objectType", 50);
		String objectName = requireText(input.objectName(), "objectName", 100);
		RiskLevel riskLevel = parseEnum(RiskLevel.class, input.riskLevel(), "riskLevel");
		DeviceOperationState operationState = parseEnum(DeviceOperationState.class,
				input.deviceOperationState(), "deviceOperationState");
		if (input.detectedAt() == null) {
			throw ApiException.validation("감지 시각을 입력해 주세요.", Map.of("detectedAt", "감지 시각은 필수입니다."));
		}
		validateMarker(input.markerX(), input.markerY());
		Double markerX = normalizeZero(input.markerX());
		Double markerY = normalizeZero(input.markerY());
		// Serialize detection merging with treatment results after validation.
		idempotencyGuard.lock("device", deviceId);

		OffsetDateTime now = OffsetDateTime.now(SERVICE_ZONE);
		Hazard hazard = new Hazard(UUID.randomUUID(), input.childId(), deviceId, HazardStatus.ACTIVE,
				objectType, objectName, riskLevel, normalizeOptional(input.riskReason()), input.detectedAt(),
				normalizeOptional(input.locationLabel(), "locationLabel", 200), normalizeOptional(input.mapImageUrl()),
				markerX, markerY, normalizeOptional(input.captureImageUrl()), operationState, eventId, now);
		idempotencyGuard.lock("hazard-detection", IdempotencyGuard.fingerprint(deviceId, eventId));
		Hazard existing = hazardRepository.findByDeviceIdAndSourceEventId(deviceId, eventId).orElse(null);
		if (existing != null) {
			if (!hazard.getDetectionInputHash().equals(existing.getDetectionInputHash())) {
				throw ApiException.conflict("DETECTION_EVENT_REUSED", "동일한 탐지 이벤트 ID에 다른 내용을 저장할 수 없습니다.");
			}
			return toDetail(existing);
		}
		// 같은 물체가 아직 미해결이면 새 건을 만들지 않고 최신 탐지로 갱신한다.
		// 탐지 1프레임마다 알림이 쌓여 실제 물체 수와 어긋나는 문제를 막는다.
		Hazard sameObject = hazardRepository
				.findFirstByDeviceIdAndChildIdAndObjectTypeAndObjectNameAndStatusOrderByDetectedAtDesc(
						deviceId, input.childId(), objectType, objectName, HazardStatus.ACTIVE)
				.orElse(null);
		if (sameObject != null) {
			sameObject.refreshFromDetection(input.detectedAt(), normalizeOptional(input.captureImageUrl()),
					operationState, now);
			return toDetail(sameObject);
		}
		return toDetail(hazardRepository.save(hazard));
	}

	private HazardListItem toListItem(Hazard hazard) {
		return new HazardListItem(hazard.getId(), hazard.getObjectName(), hazard.getRiskLevel().name(),
				hazard.getDetectedAt(), new LocationSummary(hazard.getLocationLabel(), marker(hazard)));
	}

	private HazardDetailResult toDetail(Hazard hazard) {
		return new HazardDetailResult(hazard.getId(), hazard.getDeviceId(), hazard.getStatus().name(),
				new DetectedObject(hazard.getObjectType(), hazard.getObjectName()), hazard.getRiskLevel().name(),
				hazard.getRiskReason(), hazard.getDetectedAt(),
				new LocationDetail(hazard.getLocationLabel(), hazard.getMapImageUrl(), marker(hazard)),
				hazard.getCaptureImageUrl(), hazard.getDeviceOperationState().name());
	}

	private Marker marker(Hazard hazard) {
		return hazard.getMarkerX() == null || hazard.getMarkerY() == null
				? null
				: new Marker(hazard.getMarkerX(), hazard.getMarkerY());
	}

	private String requireText(String value, String field, int maxLength) {
		String normalized = value == null ? "" : value.trim();
		if (normalized.isEmpty()) {
			throw ApiException.validation("필수 입력값을 확인해 주세요.", Map.of(field, "필수입니다."));
		}
		if (normalized.length() > maxLength) {
			throw ApiException.validation("입력값 길이를 확인해 주세요.",
					Map.of(field, maxLength + "자 이하여야 합니다."));
		}
		return normalized;
	}

	private String normalizeOptional(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.trim();
		return normalized.isEmpty() ? null : normalized;
	}

	private String normalizeOptional(String value, String field, int maxLength) {
		String normalized = normalizeOptional(value);
		if (normalized != null && normalized.length() > maxLength) {
			throw ApiException.validation("입력값 길이를 확인해 주세요.",
					Map.of(field, maxLength + "자 이하여야 합니다."));
		}
		return normalized;
	}

	private <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
		try {
			return Enum.valueOf(type, value == null ? "" : value.trim());
		}
		catch (IllegalArgumentException exception) {
			throw ApiException.validation("입력값을 확인해 주세요.", Map.of(field, "지원하지 않는 값입니다."));
		}
	}

	private void validateMarker(Double markerX, Double markerY) {
		if ((markerX == null) != (markerY == null)) {
			throw ApiException.validation("지도 좌표를 확인해 주세요.",
					Map.of("marker", "x와 y를 함께 입력해야 합니다."));
		}
		if (markerX != null && (!Double.isFinite(markerX) || !Double.isFinite(markerY)
				|| markerX < 0.0 || markerX > 1.0 || markerY < 0.0 || markerY > 1.0)) {
			throw ApiException.validation("지도 좌표를 확인해 주세요.",
					Map.of("marker", "x와 y는 0과 1 사이의 유한한 숫자여야 합니다."));
		}
	}

	private Double normalizeZero(Double value) {
		if (value == null) return null;
		return value == 0.0 ? 0.0 : value;
	}

	public record DetectionInput(UUID childId, String deviceId, String objectType, String objectName,
			String riskLevel, String riskReason, OffsetDateTime detectedAt, String locationLabel,
			String mapImageUrl, Double markerX, Double markerY, String captureImageUrl,
			String deviceOperationState, String eventId) {
	}

	public record ActiveHazardSummary(UUID hazardId, String objectName, String riskLevel,
			String locationLabel, OffsetDateTime detectedAt) {
	}

	public record ObjectDetectionCount(String objectType, String label, long count, String riskLevel) {
	}

	private record ObjectKey(String objectType, String label) {
	}

	public record HazardListItem(UUID hazardId, String objectName, String riskLevel,
			OffsetDateTime detectedAt, LocationSummary location) {
	}

	public record HazardDetailResult(UUID hazardId, String deviceId, String status,
			DetectedObject object, String riskLevel, String riskReason, OffsetDateTime detectedAt,
			LocationDetail location, String captureImageUrl, String deviceOperationState) {
	}

	public record DetectedObject(String type, String name) {
	}

	public record LocationSummary(String label, Marker marker) {
	}

	public record LocationDetail(String label, String mapImageUrl, Marker marker) {
	}

	public record Marker(double x, double y) {
	}
}
