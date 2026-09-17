package com.deni.backend.hazard;

import com.deni.backend.common.ApiException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HazardServiceTests {

	private static final OffsetDateTime DETECTED_AT = OffsetDateTime.parse("2026-09-17T10:14:00+09:00");

	@Test
	void storesDetectionAsActiveAndReturnsDetailedContract() {
		HazardRepository repository = mock(HazardRepository.class);
		when(repository.save(any(Hazard.class))).thenAnswer(invocation -> invocation.getArgument(0));
		HazardService service = new HazardService(repository);
		UUID childId = UUID.randomUUID();

		HazardService.HazardDetailResult result = service.recordDetection(new HazardService.DetectionInput(
				childId, "robot-1", "TOY_PART", "레고 브릭", "VERY_HIGH",
				"삼킴 위험이 있는 크기의 물체입니다.", DETECTED_AT, "거실 러그 위",
				"https://example.test/map.png", 0.34, 0.42,
				"https://example.test/capture.png", "PAUSED"));

		assertEquals("robot-1", result.deviceId());
		assertEquals("ACTIVE", result.status());
		assertEquals("TOY_PART", result.object().type());
		assertEquals("레고 브릭", result.object().name());
		assertEquals("VERY_HIGH", result.riskLevel());
		assertEquals(0.34, result.location().marker().x());
		assertEquals(0.42, result.location().marker().y());
		assertEquals("PAUSED", result.deviceOperationState());
	}

	@Test
	void returnsHazardsInRepositoryOrderWithNullableLocationData() {
		HazardRepository repository = mock(HazardRepository.class);
		HazardService service = new HazardService(repository);
		Hazard latest = hazard(UUID.randomUUID(), "레고 브릭", "거실", 0.3, 0.4);
		Hazard older = hazard(UUID.randomUUID(), "동전", null, null, null);
		when(repository.findByDeviceIdAndStatusOrderByDetectedAtDesc("robot-1", HazardStatus.ACTIVE))
				.thenReturn(List.of(latest, older));

		List<HazardService.HazardListItem> results = service.findHazards(" robot-1 ", HazardStatus.ACTIVE);

		assertEquals(List.of("레고 브릭", "동전"), results.stream().map(HazardService.HazardListItem::objectName).toList());
		assertEquals(0.3, results.getFirst().location().marker().x());
		assertNull(results.getLast().location().label());
		assertNull(results.getLast().location().marker());
	}

	@Test
	void returnsActiveHazardsForDashboardByChild() {
		UUID childId = UUID.randomUUID();
		HazardRepository repository = mock(HazardRepository.class);
		when(repository.findByChildIdAndStatusOrderByDetectedAtDesc(childId, HazardStatus.ACTIVE))
				.thenReturn(List.of(hazard(UUID.randomUUID(), "레고 브릭", "거실 러그 위", 0.34, 0.42)));

		List<HazardService.ActiveHazardSummary> results = new HazardService(repository)
				.findActiveHazardsForChild(childId);

		assertEquals(1, results.size());
		assertEquals("레고 브릭", results.getFirst().objectName());
		assertEquals("VERY_HIGH", results.getFirst().riskLevel());
		assertEquals("거실 러그 위", results.getFirst().locationLabel());
		assertEquals(DETECTED_AT, results.getFirst().detectedAt());
	}

	@Test
	void returnsHazardNotFoundCode() {
		UUID hazardId = UUID.randomUUID();
		HazardRepository repository = mock(HazardRepository.class);
		when(repository.findById(hazardId)).thenReturn(Optional.empty());

		ApiException exception = assertThrows(ApiException.class,
				() -> new HazardService(repository).getHazard(hazardId));

		assertEquals("HAZARD_NOT_FOUND", exception.getCode());
	}

	@Test
	void rejectsIncompleteMarkerBeforeSaving() {
		HazardRepository repository = mock(HazardRepository.class);
		HazardService service = new HazardService(repository);

		assertThrows(ApiException.class, () -> service.recordDetection(new HazardService.DetectionInput(
				UUID.randomUUID(), "robot-1", "TOY_PART", "레고 브릭", "HIGH", null,
				DETECTED_AT, null, null, 0.5, null, null, "PAUSED")));
		verify(repository, never()).save(any());
	}

	private Hazard hazard(UUID id, String objectName, String locationLabel, Double markerX, Double markerY) {
		return new Hazard(id, UUID.randomUUID(), "robot-1", HazardStatus.ACTIVE, "TOY_PART", objectName,
				RiskLevel.VERY_HIGH, "위험 사유", DETECTED_AT, locationLabel, null, markerX, markerY,
				null, DeviceOperationState.PAUSED, DETECTED_AT);
	}
}
