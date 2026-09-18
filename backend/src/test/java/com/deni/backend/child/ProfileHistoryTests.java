package com.deni.backend.child;

import com.deni.backend.common.ApiException;
import com.deni.backend.common.ApiExceptionHandler;
import com.deni.backend.common.IdempotencyGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProfileHistoryTests {

	private final Clock clock = Clock.fixed(Instant.parse("2026-09-18T03:00:00Z"), ZoneId.of("Asia/Seoul"));
	private final OffsetDateTime now = OffsetDateTime.now(clock);
	private final ChildRepository children = mock(ChildRepository.class);
	private final ProfileHistoryRepository history = mock(ProfileHistoryRepository.class);
	private final IdempotencyGuard guard = mock(IdempotencyGuard.class);
	private final ChildService service = new ChildService(children, history, guard, clock);

	@BeforeEach
	void returnManagedChildFromSave() {
		when(children.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
	}

	@Test
	void registrationStoresBaselineAndIdempotentRetryDoesNotDuplicateIt() {
		UUID key = UUID.randomUUID();
		var result = service.register("김튼튼", LocalDate.of(2025, 9, 18), key);
		var childCaptor = ArgumentCaptor.forClass(Child.class);
		verify(children).saveAndFlush(childCaptor.capture());
		var baseline = savedHistory();
		assertEquals(result.childId(), baseline.getChildId());
		assertNull(baseline.getFromStatus());
		assertNull(baseline.getFromStage());
		assertEquals(GrowthStage.TODDLER, baseline.getToStage());
		assertEquals(ProfileChangeReason.REGISTERED, baseline.getReason());
		assertTrue(baseline.getFromCriteria().isEmpty());
		assertEquals(ChildService.criteria(GrowthStage.TODDLER), baseline.getToCriteria());
		when(children.findByRegistrationIdempotencyKey(key)).thenReturn(Optional.of(childCaptor.getValue()));
		assertEquals(result.childId(), service.register("김튼튼", LocalDate.of(2025, 9, 18), key).childId());
		verify(history, times(1)).save(any());
	}

	@Test
	void registrationOutsideSupportedAgeAlsoStoresBaseline() {
		service.register("지원 범위 밖", LocalDate.of(2018, 9, 18), UUID.randomUUID());
		assertEquals(ProfileStatus.UNSUPPORTED, savedHistory().getToStatus());
		assertNull(savedHistory().getToStage());
		assertTrue(savedHistory().getToCriteria().isEmpty());
	}

	@Test
	void profileReadRecordsObservedAgeChangeOnlyOnce() {
		Child child = givenChild("2025-09-18", GrowthStage.INFANT);
		service.getSafetyProfile(child.getId());
		service.getSafetyProfile(child.getId());
		service.getDashboardChild(child.getId());

		var change = savedHistory();
		assertEquals(GrowthStage.INFANT, change.getFromStage());
		assertEquals(GrowthStage.TODDLER, change.getToStage());
		assertEquals(ProfileChangeReason.AGE_CHANGED, change.getReason());
		assertEquals(now, change.getChangedAt());
		assertTrue(change.getFromCriteria().isEmpty()); // 기존 아이의 이전 문구는 미수집
		assertEquals(now, child.getProfileAppliedAt());
		verify(history, times(1)).save(any());
	}

	@Test
	void dashboardReadRecordsChangeAndDoesNotInventIntermediateStages() {
		Child child = givenChild("2023-09-18", GrowthStage.INFANT);
		service.getDashboardChild(child.getId());
		assertEquals(GrowthStage.INFANT, savedHistory().getFromStage());
		assertEquals(GrowthStage.ACTIVE_CHILD, savedHistory().getToStage());
		verify(history, times(1)).save(any());
	}

	@Test
	void birthDateCorrectionRecordsChangeAndReusesStoredCriteriaSnapshot() {
		Child child = givenChild("2025-07-17", GrowthStage.TODDLER);
		var previousCriteria = List.of(new ChildService.SafetyCriterion("OLD", "당시 제목", "당시 설명"));
		when(history.findTopByChildIdOrderByChangedAtDescIdDesc(child.getId())).thenReturn(Optional.of(
				new ProfileHistory(child.getId(), null, null, ProfileStatus.APPLIED, GrowthStage.TODDLER,
						ProfileChangeReason.REGISTERED, now.minusMonths(1), List.of(), previousCriteria)));

		var result = service.update(child.getId(), null, LocalDate.of(2023, 9, 18));
		var change = savedHistory();
		assertEquals(ProfileChangeReason.BIRTH_DATE_UPDATED, change.getReason());
		assertEquals(previousCriteria, change.getFromCriteria());
		assertEquals(ChildService.criteria(GrowthStage.ACTIVE_CHILD), change.getToCriteria());
		assertEquals(GrowthStage.ACTIVE_CHILD, result.safetyProfile().stage());
	}

	@Test
	void unchangedStageAndNameOnlyUpdateDoNotCreateHistory() {
		Child child = givenChild("2025-07-17", GrowthStage.TODDLER);
		service.update(child.getId(), "수정된 이름", null);
		service.update(child.getId(), null, LocalDate.of(2025, 7, 18));
		service.getDashboardChild(child.getId());
		verifyNoInteractions(history);
	}

	@Test
	void recordsUnsupportedTransitionAndReturnToSupportedStage() {
		Child child = givenChild("2018-09-18", GrowthStage.ACTIVE_CHILD);
		service.getSafetyProfile(child.getId());
		assertEquals(ProfileStatus.UNSUPPORTED, child.getProfileStatus());
		assertNull(child.getProfileAppliedAt());
		service.update(child.getId(), null, LocalDate.of(2025, 9, 18));

		var captor = ArgumentCaptor.forClass(ProfileHistory.class);
		verify(history, times(2)).save(captor.capture());
		assertNull(captor.getAllValues().getFirst().getToStage());
		assertTrue(captor.getAllValues().getFirst().getToCriteria().isEmpty());
		assertEquals(ProfileStatus.UNSUPPORTED, captor.getAllValues().getLast().getFromStatus());
		assertEquals(GrowthStage.TODDLER, captor.getAllValues().getLast().getToStage());
	}

	@Test
	void invalidUpdateAndReusedRegistrationKeyDoNotCreateHistory() {
		Child child = givenChild("2025-07-17", GrowthStage.TODDLER);
		assertThrows(ApiException.class, () -> service.update(child.getId(), null, LocalDate.of(2026, 9, 19)));
		UUID key = UUID.randomUUID();
		when(children.findByRegistrationIdempotencyKey(key)).thenReturn(Optional.of(child));
		assertThrows(ApiException.class, () -> service.register("다른 이름", child.getBirthDate(), key));
		verifyNoInteractions(history);
	}

	@Test
	void delayedRegistrationRetryRefreshesProfileAndRecordsOnlyObservedChange() {
		Child child = givenChild("2023-09-18", GrowthStage.TODDLER);
		UUID key = UUID.randomUUID();
		when(children.findByRegistrationIdempotencyKey(key)).thenReturn(Optional.of(child));
		var result = service.register(child.getName(), child.getBirthDate(), key);
		assertEquals(GrowthStage.ACTIVE_CHILD, result.safetyProfile().stage());
		assertEquals(now, result.safetyProfile().appliedAt());
		assertEquals(ProfileChangeReason.AGE_CHANGED, savedHistory().getReason());
		verify(children, never()).saveAndFlush(any());
	}

	@Test
	void concurrentProfileModificationReturnsConflictInsteadOfInternalError() throws Exception {
		UUID childId = UUID.randomUUID();
		ChildService mockedService = mock(ChildService.class);
		when(mockedService.update(childId, "수정된 이름", null))
				.thenThrow(new OptimisticLockingFailureException("concurrent update"));
		var mvc = MockMvcBuilders.standaloneSetup(new ChildController(mockedService))
				.setControllerAdvice(new ApiExceptionHandler()).build();
		mvc.perform(patch("/api/v1/children/{childId}", childId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"수정된 이름\"}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CONFLICT"));
	}

	@Test
	void scheduledRefreshRecordsBoundaryChangesAndIsIdempotent() {
		var birthDates = List.of("2025-09-18", "2023-09-18", "2018-09-18");
		var previousStages = List.of(GrowthStage.INFANT, GrowthStage.TODDLER, GrowthStage.ACTIVE_CHILD);
		for (int index = 0; index < birthDates.size(); index++) {
			Child child = givenChild(birthDates.get(index), previousStages.get(index));
			assertTrue(service.refreshSafetyProfile(child.getId()));
			assertFalse(service.refreshSafetyProfile(child.getId()));
		}
		var captor = ArgumentCaptor.forClass(ProfileHistory.class);
		verify(history, times(3)).save(captor.capture());
		assertEquals(GrowthStage.TODDLER, captor.getAllValues().get(0).getToStage());
		assertEquals(GrowthStage.ACTIVE_CHILD, captor.getAllValues().get(1).getToStage());
		assertEquals(ProfileStatus.UNSUPPORTED, captor.getAllValues().get(2).getToStatus());
		assertTrue(captor.getAllValues().stream().allMatch(change -> change.getReason() == ProfileChangeReason.AGE_CHANGED));
	}

	@Test
	void originalRegistrationInputRemainsValidAfterChildWasEdited() {
		UUID key = UUID.randomUUID();
		LocalDate originalBirthDate = LocalDate.of(2025, 9, 18);
		var result = service.register("원래 이름", originalBirthDate, key);
		var captor = ArgumentCaptor.forClass(Child.class);
		verify(children).saveAndFlush(captor.capture());
		Child child = captor.getValue();
		when(children.findById(result.childId())).thenReturn(Optional.of(child));
		when(children.findByRegistrationIdempotencyKey(key)).thenReturn(Optional.of(child));
		service.update(child.getId(), "수정된 이름", LocalDate.of(2023, 9, 18));
		var replay = service.register("원래 이름", originalBirthDate, key);
		assertEquals(child.getId(), replay.childId());
		assertEquals("수정된 이름", replay.name());
		assertEquals(LocalDate.of(2023, 9, 18), replay.birthDate());
		assertThrows(ApiException.class, () -> service.register("수정된 이름", originalBirthDate, key));
		verify(children, times(1)).saveAndFlush(any());
		verify(guard, times(3)).lock("child-registration", key.toString());
	}

	@Test
	void registrationRequiresKeyBeforeAnyStorageOperation() {
		assertThrows(ApiException.class, () -> service.register("이름", LocalDate.of(2025, 9, 18), null));
		verifyNoInteractions(children, history, guard);
	}

	private Child givenChild(String birthDate, GrowthStage stage) {
		var child = new Child(UUID.randomUUID(), "김튼튼", LocalDate.parse(birthDate), ProfileStatus.APPLIED,
				stage, now.minusMonths(1), UUID.randomUUID(), now.minusMonths(1));
		when(children.findById(child.getId())).thenReturn(Optional.of(child));
		return child;
	}

	private ProfileHistory savedHistory() {
		var captor = ArgumentCaptor.forClass(ProfileHistory.class);
		verify(history).save(captor.capture());
		return captor.getValue();
	}
}
