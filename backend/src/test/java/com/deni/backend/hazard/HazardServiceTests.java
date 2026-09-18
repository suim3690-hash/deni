package com.deni.backend.hazard;

import com.deni.backend.common.ApiException;
import com.deni.backend.common.IdempotencyGuard;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;

class HazardServiceTests {

	private final IdempotencyGuard guard = mock(IdempotencyGuard.class);

	private static final OffsetDateTime DETECTED_AT = OffsetDateTime.parse("2026-09-17T10:14:00+09:00");

	@Test
	void storesDetectionAsActiveAndReturnsDetailedContract() {
		HazardRepository repository = mock(HazardRepository.class);
		when(repository.save(any(Hazard.class))).thenAnswer(invocation -> invocation.getArgument(0));
		HazardService service = new HazardService(repository, guard);
		UUID childId = UUID.randomUUID();

		HazardService.HazardDetailResult result = service.recordDetection(new HazardService.DetectionInput(
				childId, "robot-1", "TOY_PART", "레고 브릭", "VERY_HIGH",
				"삼킴 위험이 있는 크기의 물체입니다.", DETECTED_AT, "거실 러그 위",
				"https://example.test/map.png", 0.34, 0.42,
				"https://example.test/capture.png", "PAUSED", "event-1"));

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
		HazardService service = new HazardService(repository, guard);
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

		List<HazardService.ActiveHazardSummary> results = new HazardService(repository, guard)
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
				() -> new HazardService(repository, guard).getHazard(hazardId));

		assertEquals("HAZARD_NOT_FOUND", exception.getCode());
	}

	@Test
	void rejectsIncompleteMarkerBeforeSaving() {
		HazardRepository repository = mock(HazardRepository.class);
		HazardService service = new HazardService(repository, guard);

		assertThrows(ApiException.class, () -> service.recordDetection(new HazardService.DetectionInput(
				UUID.randomUUID(), "robot-1", "TOY_PART", "레고 브릭", "HIGH", null,
				DETECTED_AT, null, null, 0.5, null, null, "PAUSED", "event-1")));
		verify(repository, never()).save(any());
	}

	@Test
	void groupsMonthlyCountsByTypeAndNameAndKeepsHighestRisk() {
		UUID childId = UUID.randomUUID();
		OffsetDateTime end = DETECTED_AT.plusMonths(1);
		HazardRepository repository = mock(HazardRepository.class);
		var rows = List.of(
				count("TOY_PART", "레고", RiskLevel.LOW, 2),
				count("TOY_PART", "레고", RiskLevel.VERY_HIGH, 1),
				count("COIN", "동전", RiskLevel.HIGH, 3),
				count("OTHER", "레고", RiskLevel.MEDIUM, 1));
		when(repository.countDetectionsByObject(childId, DETECTED_AT, end)).thenReturn(rows);

		var results = new HazardService(repository, guard).getMonthlyDetections(childId, DETECTED_AT, end);

		assertEquals(List.of(
				new HazardService.ObjectDetectionCount("COIN", "동전", 3, "HIGH"),
				new HazardService.ObjectDetectionCount("TOY_PART", "레고", 3, "VERY_HIGH"),
				new HazardService.ObjectDetectionCount("OTHER", "레고", 1, "MEDIUM")), results);
		verify(repository).countDetectionsByObject(childId, DETECTED_AT, end);
	}

	@Test
	void returnsEmptyMonthlyCountsWhenNoDetectionsExist() {
		HazardRepository repository = mock(HazardRepository.class);
		when(repository.countDetectionsByObject(any(), any(), any())).thenReturn(List.of());
		assertEquals(List.of(), new HazardService(repository, guard)
				.getMonthlyDetections(UUID.randomUUID(), DETECTED_AT, DETECTED_AT.plusMonths(1)));
	}

	@Test
	void rejectsNonFiniteOutOfRangeAndUnpairedCoordinatesBeforeLocking() {
		HazardRepository repository = mock(HazardRepository.class);
		HazardService service = new HazardService(repository, guard);
		UUID childId = UUID.randomUUID();
		Double[][] invalid = {{Double.NaN, 0.5}, {0.5, Double.NaN}, {Double.POSITIVE_INFINITY, 0.5},
				{0.5, Double.NEGATIVE_INFINITY}, {-0.01, 0.5}, {0.5, 1.01}, {null, 0.5}, {0.5, null}};
		for (Double[] coordinates : invalid) {
			ApiException error = assertThrows(ApiException.class, () -> service.recordDetection(
					input(childId, "robot-1", "event-1", "레고", coordinates[0], coordinates[1], DETECTED_AT)));
			assertEquals("VALIDATION_ERROR", error.getCode());
			assertEquals(400, error.getStatus().value());
		}
		verifyNoInteractions(repository, guard);
	}

	@Test
	void acceptsCoordinateBoundariesAndNormalizesNegativeZero() {
		HazardRepository repository = mock(HazardRepository.class);
		when(repository.save(any(Hazard.class))).thenAnswer(invocation -> invocation.getArgument(0));
		var result = new HazardService(repository, guard).recordDetection(
				input(UUID.randomUUID(), "robot-1", "event-1", "레고", -0.0, 1.0, DETECTED_AT));
		assertEquals(0.0, result.location().marker().x());
		assertEquals(1.0, result.location().marker().y());
	}

	@Test
	void normalizedReplayReturnsSameHazardWithEquivalentTimestampAndNoNewRow() {
		HazardRepository repository = mock(HazardRepository.class);
		UUID childId = UUID.randomUUID();
		Hazard stored = new Hazard(UUID.randomUUID(), childId, "robot-1", HazardStatus.ACTIVE, "TOY_PART", "레고",
				RiskLevel.HIGH, null, DETECTED_AT, null, null, 0.0, 1.0, null, DeviceOperationState.PAUSED,
				"event-1", DETECTED_AT);
		when(repository.findByDeviceIdAndSourceEventId("robot-1", "event-1")).thenReturn(Optional.of(stored));
		var result = new HazardService(repository, guard).recordDetection(
				input(childId, " robot-1 ", " event-1 ", " 레고 ", -0.0, 1.0,
						DETECTED_AT.withOffsetSameInstant(java.time.ZoneOffset.UTC)));
		assertEquals(stored.getId(), result.hazardId());
		verify(repository, never()).save(any());
		verify(guard).lock("hazard-detection", IdempotencyGuard.fingerprint("robot-1", "event-1"));
	}

	@Test
	void replayDoesNotReactivateResolvedHazard() {
		HazardRepository repository = mock(HazardRepository.class);
		UUID childId = UUID.randomUUID();
		Hazard resolved = new Hazard(UUID.randomUUID(), childId, "robot-1", HazardStatus.RESOLVED, "TOY_PART", "레고",
				RiskLevel.HIGH, null, DETECTED_AT, null, null, null, null, null, DeviceOperationState.PAUSED,
				"event-1", DETECTED_AT);
		when(repository.findByDeviceIdAndSourceEventId("robot-1", "event-1")).thenReturn(Optional.of(resolved));
		var result = new HazardService(repository, guard).recordDetection(
				input(childId, "robot-1", "event-1", "레고", null, null, DETECTED_AT));
		assertEquals("RESOLVED", result.status());
		verify(repository, never()).save(any());
	}

	@Test
	void changedContentOnSameEventIsRejectedAndDifferentEventsAreStoredSeparately() {
		HazardRepository repository = mock(HazardRepository.class);
		UUID childId = UUID.randomUUID();
		Hazard stored = new Hazard(UUID.randomUUID(), childId, "robot-1", HazardStatus.ACTIVE, "TOY_PART", "레고",
				RiskLevel.HIGH, null, DETECTED_AT, null, null, null, null, null, DeviceOperationState.PAUSED,
				"event-1", DETECTED_AT);
		when(repository.findByDeviceIdAndSourceEventId("robot-1", "event-1")).thenReturn(Optional.of(stored));
		when(repository.save(any(Hazard.class))).thenAnswer(invocation -> invocation.getArgument(0));
		HazardService service = new HazardService(repository, guard);
		var error = assertThrows(ApiException.class, () -> service.recordDetection(
				input(childId, "robot-1", "event-1", "동전", null, null, DETECTED_AT)));
		assertEquals("DETECTION_EVENT_REUSED", error.getCode());
		service.recordDetection(input(childId, "robot-1", "event-2", "레고", null, null, DETECTED_AT));
		service.recordDetection(input(childId, "robot-2", "event-1", "레고", null, null, DETECTED_AT));
		verify(repository, times(2)).save(any());
	}

	@Test
	void eventIdIsRequiredAndLengthLimitedForNewDetections() {
		HazardRepository repository = mock(HazardRepository.class);
		HazardService service = new HazardService(repository, guard);
		for (String event : new String[]{null, " ", "a".repeat(101)}) {
			assertThrows(ApiException.class, () -> service.recordDetection(
					input(UUID.randomUUID(), "robot-1", event, "레고", null, null, DETECTED_AT)));
		}
		assertThrows(ApiException.class, () -> service.recordDetection(null));
		verifyNoInteractions(repository, guard);
	}

	@Test
	void safetyRequestRequiresMatchingChildDeviceAndActiveHazard() {
		HazardRepository repository = mock(HazardRepository.class);
		HazardService service = new HazardService(repository, guard);
		UUID id = UUID.randomUUID();
		UUID child = UUID.randomUUID();
		Hazard hazard = mock(Hazard.class);
		when(repository.findById(id)).thenReturn(java.util.Optional.of(hazard));
		when(hazard.getChildId()).thenReturn(child);
		when(hazard.getDeviceId()).thenReturn("robot-1");
		when(hazard.getStatus()).thenReturn(HazardStatus.ACTIVE);
		service.requireActiveAssociation(id, child, "robot-1");
		assertEquals("HAZARD_DEVICE_MISMATCH", assertThrows(ApiException.class,
				() -> service.requireActiveAssociation(id, UUID.randomUUID(), "robot-1")).getCode());
		assertEquals("HAZARD_DEVICE_MISMATCH", assertThrows(ApiException.class,
				() -> service.requireActiveAssociation(id, child, "other")).getCode());
		when(hazard.getStatus()).thenReturn(HazardStatus.RESOLVED);
		assertEquals("HAZARD_ALREADY_RESOLVED", assertThrows(ApiException.class,
				() -> service.requireActiveAssociation(id, child, "robot-1")).getCode());
	}

	private HazardService.DetectionInput input(UUID childId, String device, String event, String name,
			Double x, Double y, OffsetDateTime detectedAt) {
		return new HazardService.DetectionInput(childId, device, "TOY_PART", name, "HIGH", null, detectedAt,
				null, null, x, y, null, "PAUSED", event);
	}

	private HazardRepository.DetectionCount count(String type, String name, RiskLevel risk, long count) {
		var result = mock(HazardRepository.DetectionCount.class);
		when(result.getObjectType()).thenReturn(type);
		when(result.getLabel()).thenReturn(name);
		when(result.getRiskLevel()).thenReturn(risk.name());
		when(result.getCount()).thenReturn(count);
		return result;
	}

	private Hazard hazard(UUID id, String objectName, String locationLabel, Double markerX, Double markerY) {
		return new Hazard(id, UUID.randomUUID(), "robot-1", HazardStatus.ACTIVE, "TOY_PART", objectName,
				RiskLevel.VERY_HIGH, "위험 사유", DETECTED_AT, locationLabel, null, markerX, markerY,
				null, DeviceOperationState.PAUSED, null, DETECTED_AT);
	}
}
