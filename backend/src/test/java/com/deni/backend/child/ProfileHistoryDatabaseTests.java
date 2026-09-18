package com.deni.backend.child;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 명시적으로 실행할 때만 제공 DB에 연결한다. 테스트 행은 각 테스트 종료 시 롤백한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = "safety.profile-refresh.enabled=false")
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
@Transactional
class ProfileHistoryDatabaseTests {

	private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
	@Autowired private ChildService children;
	@Autowired private MonthlyReportService reports;
	@Autowired private ProfileHistoryRepository history;
	@Autowired private EntityManager entityManager;
	@Autowired private ChildRepository childRepository;

	@Test
	void scheduledServicePersistsBoundaryChangesWithoutFrontendQueriesOrDuplicateHistory() {
		LocalDate today = LocalDate.now(SERVICE_ZONE);
		OffsetDateTime previousTime = OffsetDateTime.now(SERVICE_ZONE).minusDays(1);
		var previousStages = List.of(GrowthStage.INFANT, GrowthStage.TODDLER, GrowthStage.ACTIVE_CHILD);
		var boundaryMonths = List.of(12, 36, 96);
		for (int index = 0; index < boundaryMonths.size(); index++) {
			UUID childId = UUID.randomUUID();
			entityManager.persist(new Child(childId, "PROFILE_SCHEDULE_TEST",
					today.withDayOfMonth(1).minusMonths(boundaryMonths.get(index)), ProfileStatus.APPLIED,
					previousStages.get(index), previousTime, UUID.randomUUID(), previousTime));
			entityManager.flush();
			entityManager.clear();

			assertTrue(children.refreshSafetyProfile(childId));
			assertFalse(children.refreshSafetyProfile(childId));
			entityManager.flush();
			entityManager.clear();
			var report = reports.getMonthlyReport(childId, YearMonth.from(today).toString());
			assertEquals(1, report.stageChanges().size());
			assertEquals("AGE_CHANGED", report.stageChange().reason());
			assertEquals(previousStages.get(index).name(), report.stageChange().from());
		}
	}

	@Test
	void profileRefreshIdQueryPaginatesInStableUuidOrder() {
		var first = childRepository.findIdsForProfileRefresh(PageRequest.of(0, 2));
		var second = childRepository.findIdsForProfileRefresh(PageRequest.of(1, 2));
		var expected = childRepository.findAll().stream().map(Child::getId)
				.sorted(java.util.Comparator.comparing(UUID::toString)).limit(4).toList();
		var actual = java.util.stream.Stream.concat(first.getContent().stream(), second.getContent().stream()).toList();
		assertEquals(expected, actual);
		assertEquals(expected.size() > 2, first.hasNext());
	}

	@Test
	void persistsSnapshotsAndReturnsMultipleChangesAfterReloadWithoutDuplicateRetries() {
		LocalDate today = LocalDate.now(SERVICE_ZONE);
		UUID key = UUID.randomUUID();
		LocalDate birthDate = today.withDayOfMonth(1).minusMonths(20);
		var registered = children.register("PROFILE_HISTORY_TEST", birthDate, key);
		children.register("PROFILE_HISTORY_TEST", birthDate, key);
		children.update(registered.childId(), "PROFILE_HISTORY_TEST_RENAMED", null);
		children.update(registered.childId(), null, today.withDayOfMonth(1).minusMonths(40));
		children.update(registered.childId(), null, today.withDayOfMonth(1).minusMonths(100));
		entityManager.flush();
		entityManager.clear();

		var report = reports.getMonthlyReport(registered.childId(), YearMonth.from(today).toString());
		assertEquals(2, report.stageChanges().size());
		assertEquals("TODDLER", report.stageChanges().getFirst().from());
		assertEquals("ACTIVE_CHILD", report.stageChanges().getFirst().to());
		assertEquals("BIRTH_DATE_UPDATED", report.stageChanges().getFirst().reason());
		assertNull(report.stageChanges().getLast().to());
		assertEquals("UNSUPPORTED", report.stageChanges().getLast().toStatus());
		assertEquals(report.stageChanges().getLast(), report.stageChange());
		assertEquals(ChildService.criteria(GrowthStage.ACTIVE_CHILD).getFirst().description(),
				report.criteriaChanges().getFirst().description());
		var latest = history.findTopByChildIdOrderByChangedAtDescIdDesc(registered.childId()).orElseThrow();
		assertEquals(ChildService.criteria(GrowthStage.ACTIVE_CHILD), latest.getFromCriteria());
		assertTrue(latest.getToCriteria().isEmpty());
	}

	@Test
	void databaseMonthRangeUsesSeoulBoundariesAndStableOrderForIdenticalTimestamps() {
		LocalDate today = LocalDate.now(SERVICE_ZONE);
		UUID childId = children.register("PROFILE_RANGE_TEST", today.minusMonths(20), UUID.randomUUID()).childId();
		OffsetDateTime start = OffsetDateTime.parse("2025-12-01T00:00:00+09:00");
		OffsetDateTime end = OffsetDateTime.parse("2026-01-01T00:00:00+09:00");
		var infantCriteria = ChildService.criteria(GrowthStage.INFANT);
		var toddlerCriteria = ChildService.criteria(GrowthStage.TODDLER);
		history.saveAndFlush(new ProfileHistory(childId, ProfileStatus.APPLIED, GrowthStage.INFANT,
				ProfileStatus.APPLIED, GrowthStage.TODDLER, ProfileChangeReason.AGE_CHANGED,
				start.minusSeconds(1), infantCriteria, toddlerCriteria));
		history.saveAndFlush(new ProfileHistory(childId, ProfileStatus.APPLIED, GrowthStage.INFANT,
				ProfileStatus.APPLIED, GrowthStage.TODDLER, ProfileChangeReason.AGE_CHANGED,
				start, infantCriteria, toddlerCriteria));
		history.saveAndFlush(new ProfileHistory(childId, ProfileStatus.APPLIED, GrowthStage.TODDLER,
				ProfileStatus.APPLIED, GrowthStage.INFANT, ProfileChangeReason.BIRTH_DATE_UPDATED,
				start, toddlerCriteria, infantCriteria));
		history.saveAndFlush(new ProfileHistory(childId, ProfileStatus.APPLIED, GrowthStage.INFANT,
				ProfileStatus.APPLIED, GrowthStage.TODDLER, ProfileChangeReason.AGE_CHANGED,
				end, infantCriteria, toddlerCriteria));
		entityManager.clear();

		var rows = history.findByChildIdAndChangedAtGreaterThanEqualAndChangedAtLessThanOrderByChangedAtAscIdAsc(
				childId, start, end);
		assertEquals(List.of(GrowthStage.TODDLER, GrowthStage.INFANT), rows.stream()
				.map(ProfileHistory::getToStage).toList());
		assertTrue(rows.stream().allMatch(row -> row.getChangedAt().isEqual(start)));
		UUID otherChild = children.register("PROFILE_OTHER_TEST", today.minusMonths(20), UUID.randomUUID()).childId();
		assertTrue(history.findByChildIdAndChangedAtGreaterThanEqualAndChangedAtLessThanOrderByChangedAtAscIdAsc(
				otherChild, start, end).isEmpty());
	}
}
