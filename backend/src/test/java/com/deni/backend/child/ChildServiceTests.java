package com.deni.backend.child;

import com.deni.backend.common.IdempotencyGuard;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChildServiceTests {

	private static final LocalDate TODAY = LocalDate.of(2026, 9, 17);

	@Test
	void calculatesGrowthStageAtEveryBoundary() {
		assertProfile(LocalDate.of(2025, 9, 18), 11, GrowthStage.INFANT);
		assertProfile(LocalDate.of(2025, 9, 17), 12, GrowthStage.TODDLER);
		assertProfile(LocalDate.of(2023, 9, 18), 35, GrowthStage.TODDLER);
		assertProfile(LocalDate.of(2023, 9, 17), 36, GrowthStage.ACTIVE_CHILD);
		assertProfile(LocalDate.of(2018, 9, 18), 95, GrowthStage.ACTIVE_CHILD);

		ChildService.CalculatedProfile unsupported = ChildService.calculateProfile(
				LocalDate.of(2018, 9, 17), TODAY);
		assertEquals(96, unsupported.ageMonths());
		assertEquals(ProfileStatus.UNSUPPORTED, unsupported.status());
		assertNull(unsupported.stage());
	}

	@Test
	void returnsApprovedCriteriaForStoredChildProfile() {
		UUID childId = UUID.randomUUID();
		LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
		LocalDate birthDate = today.withDayOfMonth(1).minusMonths(20);
		OffsetDateTime appliedAt = OffsetDateTime.now(ZoneId.of("Asia/Seoul"));
		Child child = new Child(childId, "김튼튼", birthDate, ProfileStatus.APPLIED, GrowthStage.TODDLER,
				appliedAt, UUID.randomUUID(), appliedAt);
		ChildRepository repository = mock(ChildRepository.class);
		when(repository.findById(childId)).thenReturn(Optional.of(child));

		ChildService.SafetyProfileResult result = new ChildService(repository,
				mock(ProfileHistoryRepository.class), mock(IdempotencyGuard.class)).getSafetyProfile(childId);

		assertEquals(childId, result.childId());
		assertEquals(ProfileStatus.APPLIED, result.status());
		assertEquals(GrowthStage.TODDLER, result.stage());
		assertEquals("걸음마 시기", result.stageLabel());
		assertEquals(20, result.ageMonths());
		assertEquals(1, result.criteria().size());
		assertEquals("MOVEMENT_HAZARD", result.criteria().getFirst().code());
	}

	private void assertProfile(LocalDate birthDate, int expectedMonths, GrowthStage expectedStage) {
		ChildService.CalculatedProfile profile = ChildService.calculateProfile(birthDate, TODAY);
		assertEquals(expectedMonths, profile.ageMonths());
		assertEquals(ProfileStatus.APPLIED, profile.status());
		assertEquals(expectedStage, profile.stage());
	}
}
