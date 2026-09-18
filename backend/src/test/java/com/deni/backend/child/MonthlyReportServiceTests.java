package com.deni.backend.child;

import com.deni.backend.common.ApiException;
import com.deni.backend.hazard.HazardService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MonthlyReportServiceTests {

	private final UUID childId = UUID.randomUUID();
	private final ChildRepository repository = mock(ChildRepository.class);
	private final HazardService hazards = mock(HazardService.class);
	private final ProfileHistoryRepository history = mock(ProfileHistoryRepository.class);
	private final Clock clock = Clock.fixed(Instant.parse("2026-09-18T03:00:00Z"), ZoneId.of("Asia/Seoul"));
	private final MonthlyReportService service = new MonthlyReportService(repository, hazards, history, clock);

	@Test
	void returnsEmptyReportWithoutInventedMetricsOrHistory() {
		givenChild("2025-07-17");
		when(hazards.getMonthlyDetections(any(), any(), any())).thenReturn(List.of());

		var report = service.getMonthlyReport(childId, "2026-09");

		assertEquals("report_" + childId + "_2026-09", report.reportId());
		assertEquals(childId, report.childId());
		assertEquals("김튼튼", report.childName());
		assertEquals(0, report.summary().detectionCount());
		assertNull(report.summary().avoidanceRatePercent());
		assertNull(report.summary().safeCleanedAreaSquareMeters());
		assertNull(report.stageChange());
		assertTrue(report.stageChanges().isEmpty());
		assertTrue(report.criteriaChanges().isEmpty());
		assertNull(report.feedback());
		assertEquals("ACTIVE_CHILD", report.nextStagePreview().stage());
		verify(hazards).getMonthlyDetections(childId,
				OffsetDateTime.parse("2026-09-01T00:00:00+09:00"),
				OffsetDateTime.parse("2026-10-01T00:00:00+09:00"));
	}

	@Test
	void sumsObjectCountsAndHandlesYearBoundary() {
		givenChild("2025-07-17");
		var detections = List.of(new HazardService.ObjectDetectionCount("TOY_PART", "레고", 3, "HIGH"),
				new HazardService.ObjectDetectionCount("COIN", "동전", 2, "MEDIUM"));
		when(hazards.getMonthlyDetections(any(), any(), any())).thenReturn(detections);

		var report = service.getMonthlyReport(childId, "2025-12");

		assertEquals(5, report.summary().detectionCount());
		assertEquals(detections, report.detectionsByObject());
		assertEquals("TODDLER", report.nextStagePreview().stage());
		verify(hazards).getMonthlyDetections(childId,
				OffsetDateTime.parse("2025-12-01T00:00:00+09:00"),
				OffsetDateTime.parse("2026-01-01T00:00:00+09:00"));
	}

	@Test
	void rejectsInvalidAndFutureMonthsBeforeQuerying() {
		for (String month : List.of("2026-9", "2026-13", "no-month", "0000-01", "2026-10")) {
			ApiException error = assertThrows(ApiException.class, () -> service.getMonthlyReport(childId, month));
			assertEquals(400, error.getStatus().value());
			assertEquals("VALIDATION_ERROR", error.getCode());
			assertTrue(error.getFieldErrors().containsKey("month"));
		}
		verifyNoInteractions(repository, hazards, history);
	}

	@Test
	void returnsChildNotFoundWithoutQueryingHazards() {
		when(repository.findById(childId)).thenReturn(Optional.empty());
		var error = assertThrows(ApiException.class, () -> service.getMonthlyReport(childId, "2026-09"));
		assertEquals("CHILD_NOT_FOUND", error.getCode());
		verifyNoInteractions(hazards, history);
	}

	@Test
	void doesNotForecastBeforeBirthOrBeyondSupportedStages() {
		for (String birthDate : List.of("2026-09-19", "2022-01-01", "2018-01-01")) {
			givenChild(birthDate);
			when(hazards.getMonthlyDetections(any(), any(), any())).thenReturn(List.of());
			assertNull(service.getMonthlyReport(childId, "2026-09").nextStagePreview().stage());
		}
	}

	@Test
	void dashboardSummaryHasSameStableIdentityAsCurrentReport() {
		var summary = service.getCurrentSummary(childId);
		assertEquals("2026-09", summary.month());
		assertEquals("report_" + childId + "_2026-09", summary.reportId());
		assertTrue(summary.available());
		verifyNoInteractions(repository, hazards, history);
	}

	@Test
	void returnsEveryStoredChangeAndLatestCompatibilityFieldWithoutCountingRegistration() {
		givenChild("2025-07-17");
		var registeredAt = OffsetDateTime.parse("2026-09-01T00:00:00+09:00");
		var changedAt = registeredAt.plusDays(1);
		var snapshot = List.of(new ChildService.SafetyCriterion("OLD_CRITERION", "저장된 제목", "저장된 설명"));
		var rows = List.of(
				new ProfileHistory(childId, null, null, ProfileStatus.APPLIED, GrowthStage.INFANT,
						ProfileChangeReason.REGISTERED, registeredAt, List.of(), snapshot),
				new ProfileHistory(childId, ProfileStatus.APPLIED, GrowthStage.INFANT, ProfileStatus.APPLIED,
						GrowthStage.TODDLER, ProfileChangeReason.AGE_CHANGED, changedAt, snapshot, snapshot),
				new ProfileHistory(childId, ProfileStatus.APPLIED, GrowthStage.TODDLER, ProfileStatus.UNSUPPORTED,
						null, ProfileChangeReason.BIRTH_DATE_UPDATED, changedAt.plusDays(1), snapshot, List.of()));
		when(history.findByChildIdAndChangedAtGreaterThanEqualAndChangedAtLessThanOrderByChangedAtAscIdAsc(
				any(), any(), any())).thenReturn(rows);

		var report = service.getMonthlyReport(childId, "2026-09");

		assertEquals(2, report.stageChanges().size());
		assertEquals("INFANT", report.stageChanges().getFirst().from());
		assertEquals("TODDLER", report.stageChanges().getFirst().to());
		assertEquals("AGE_CHANGED", report.stageChanges().getFirst().reason());
		assertEquals(report.stageChanges().getLast(), report.stageChange());
		assertEquals("UNSUPPORTED", report.stageChange().toStatus());
		assertNull(report.stageChange().to());
		assertEquals("저장된 제목", report.criteriaChanges().getFirst().title());
		assertEquals("저장된 설명", report.criteriaChanges().getFirst().description());
		assertEquals(changedAt, report.criteriaChanges().getFirst().changedAt());
		assertEquals("지원 범위 밖 전환", report.criteriaChanges().getLast().title());
		verify(history).findByChildIdAndChangedAtGreaterThanEqualAndChangedAtLessThanOrderByChangedAtAscIdAsc(
				childId, registeredAt, OffsetDateTime.parse("2026-10-01T00:00:00+09:00"));
	}

	@Test
	void initialRegistrationIsNotAStageOrCriteriaChange() {
		givenChild("2025-07-17");
		when(history.findByChildIdAndChangedAtGreaterThanEqualAndChangedAtLessThanOrderByChangedAtAscIdAsc(
				any(), any(), any())).thenReturn(List.of(new ProfileHistory(childId, null, null,
				ProfileStatus.APPLIED, GrowthStage.TODDLER, ProfileChangeReason.REGISTERED,
				OffsetDateTime.now(clock), List.of(), ChildService.criteria(GrowthStage.TODDLER))));
		var report = service.getMonthlyReport(childId, "2026-09");
		assertTrue(report.stageChanges().isEmpty());
		assertNull(report.stageChange());
		assertTrue(report.criteriaChanges().isEmpty());
	}

	private void givenChild(String birthDate) {
		when(repository.findById(childId)).thenReturn(Optional.of(new Child(childId, "김튼튼",
				LocalDate.parse(birthDate), ProfileStatus.APPLIED, GrowthStage.TODDLER,
				OffsetDateTime.now(clock), UUID.randomUUID(), OffsetDateTime.now(clock))));
	}
}
