package com.deni.backend.common;

import com.deni.backend.child.ChildService;
import com.deni.backend.child.MonthlyReportService;
import com.deni.backend.hazard.HazardService;
import com.deni.backend.device.DeviceService;
import com.deni.backend.operation.OperationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/** 동시 요청은 실제 커밋이 필요하다. 고유 테스트 키로 만든 행만 finally에서 정리한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = "safety.profile-refresh.enabled=false")
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
class StorageIdempotencyDatabaseTests {

	private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
	@Autowired private ChildService children;
	@Autowired private HazardService hazards;
	@Autowired private DeviceService devices;
	@Autowired private OperationService operations;
	@Autowired private MonthlyReportService reports;
	@Autowired private IdempotencyGuard guard;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private PlatformTransactionManager transactionManager;

	@Test
	void concurrentRegistrationCreatesOneChildAndBaselineAndOriginalRetrySurvivesEdit() throws Exception {
		UUID key = UUID.randomUUID();
		LocalDate birthDate = LocalDate.now(SERVICE_ZONE).minusMonths(20);
		try {
			var results = runConcurrent(() -> children.register("STORAGE_REGISTRATION_TEST", birthDate, key));
			UUID childId = results.getFirst().childId();
			assertEquals(childId, results.getLast().childId());
			assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM children WHERE registration_idempotency_key = ?",
					Long.class, key));
			assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM profile_history WHERE child_id = ? AND reason = 'REGISTERED'",
					Long.class, childId));
			children.update(childId, "STORAGE_RENAMED_TEST", birthDate.minusMonths(20));
			var replay = children.register("STORAGE_REGISTRATION_TEST", birthDate, key);
			assertEquals(childId, replay.childId());
			assertEquals("STORAGE_RENAMED_TEST", replay.name());
			assertEquals(birthDate.minusMonths(20), replay.birthDate());
			var conflict = assertThrows(ApiException.class, () -> children.register("OTHER_INPUT", birthDate, key));
			assertEquals("IDEMPOTENCY_KEY_REUSED", conflict.getCode());
		}
		finally {
			cleanupTestRegistration(key);
		}
	}

	@Test
	void concurrentDetectionStoresOneRowAndDoesNotInflateMonthlyReport() throws Exception {
		UUID key = UUID.randomUUID();
		try {
			UUID childId = children.register("STORAGE_DETECTION_TEST", LocalDate.now(SERVICE_ZONE).minusMonths(20), key).childId();
			String device = "storage-test-" + UUID.randomUUID();
			String event = UUID.randomUUID().toString();
			OffsetDateTime detectedAt = OffsetDateTime.now(SERVICE_ZONE);
			var results = runConcurrent(() -> hazards.recordDetection(input(childId, device, event, "레고", detectedAt)));
			UUID hazardId = results.getFirst().hazardId();
			assertEquals(hazardId, results.getLast().hazardId());
			assertEquals(1L, reports.getMonthlyReport(childId, YearMonth.from(detectedAt).toString()).summary().detectionCount());
			var conflict = assertThrows(ApiException.class, () -> hazards.recordDetection(input(childId, device, event, "동전", detectedAt)));
			assertEquals("DETECTION_EVENT_REUSED", conflict.getCode());
			assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
					INSERT INTO hazards (id, child_id, device_id, status, object_type, object_name, risk_level,
					    detected_at, device_operation_state, created_at, updated_at, source_event_id, detection_input_hash)
					SELECT ?, child_id, device_id, status, object_type, object_name, risk_level,
					    detected_at, device_operation_state, created_at, updated_at, source_event_id, detection_input_hash
					FROM hazards WHERE id = ?
					""", UUID.randomUUID(), hazardId));
			// 같은 기기에서 같은 물체를 다시 보면 미해결 건 하나로 합쳐진다. 월간 집계는 프레임 수가 아니라
			// 실제 위험물 건수를 센다. 다른 기기에서 본 같은 물체는 별개 건이다.
			hazards.recordDetection(input(childId, device, "different-event", "레고", detectedAt));
			hazards.recordDetection(input(childId, device + "-other", event, "레고", detectedAt));
			assertEquals(2L, reports.getMonthlyReport(childId, YearMonth.from(detectedAt).toString()).summary().detectionCount());
		}
		finally {
			cleanupTestRegistration(key);
		}
	}

	@Test
	void advisoryLockRequiresAnActiveTransaction() {
		assertThrows(IllegalTransactionStateException.class, () -> guard.lock("test", UUID.randomUUID().toString()));
	}

	@Test
	void concurrentDeviceRegistrationStoresOneLinkWithoutInventingStatus() throws Exception {
		UUID key = UUID.randomUUID();
		try {
			UUID childId = children.register("DEVICE_STORAGE_TEST", LocalDate.now(SERVICE_ZONE).minusMonths(20), key).childId();
			String id = "storage-device-" + UUID.randomUUID();
			var results = runConcurrent(() -> devices.register(childId, id, "동시 등록 테스트"));
			assertEquals(results.getFirst().deviceId(), results.getLast().deviceId());
			assertEquals("UNKNOWN", results.getLast().status().connectionState());
			assertNull(results.getLast().status().lastSeenAt());
			assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE child_id = ?", Long.class, childId));
			assertEquals("CHILD_DEVICE_ALREADY_LINKED", assertThrows(ApiException.class,
					() -> devices.register(childId, "different-" + UUID.randomUUID(), "두번째 기기")).getCode());
		}
		finally {
			cleanupTestRegistration(key);
		}
	}

	@Test
	void concurrentPauseRequestsStoreOneReceipt() throws Exception {
		UUID key = UUID.randomUUID();
		try {
			UUID child = children.register("OPERATION_STORAGE_TEST", LocalDate.now(SERVICE_ZONE).minusMonths(20), key).childId();
			String device = "operation-storage-" + UUID.randomUUID();
			devices.register(child, device, "명령 동시 요청 테스트");
			devices.recordStatus(new DeviceService.StatusInput(device, "ONLINE", "RUNNING", 82, OffsetDateTime.now(SERVICE_ZONE).minusSeconds(1)));
			UUID requestKey = UUID.randomUUID();
			var result = runConcurrent(() -> operations.requestCommand(device, "pause", requestKey));
			assertEquals(result.getFirst().commandId(), result.getLast().commandId());
			assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM operation_requests WHERE device_id = ?", Long.class, device));
			assertEquals("RUNNING", devices.getStatus(device).operationState());
		}
		finally { cleanupTestRegistration(key); }
	}

	@Test
	void concurrentRemovalRequestsStoreOneIntentWithoutResolvingHazard() throws Exception {
		UUID key = UUID.randomUUID();
		try {
			UUID child = children.register("OPERATION_STORAGE_TEST", LocalDate.now(SERVICE_ZONE).minusMonths(20), key).childId();
			String device = "operation-storage-" + UUID.randomUUID();
			devices.register(child, device, "재확인 동시 요청 테스트");
			devices.recordStatus(new DeviceService.StatusInput(device, "ONLINE", "PAUSED", 82, OffsetDateTime.now(SERVICE_ZONE).minusSeconds(1)));
			jdbc.update("""
					INSERT INTO robot_live_state(device_id, operation_state, movement_state, sampled_at, power_enabled, task_state)
					VALUES (?, 'PAUSED', 'STOPPED', clock_timestamp(), TRUE, 'HAZARD_PAUSED')
					""", device);
			// 직접 제거 재확인은 삼킴 위험물에만 접수된다.
			UUID hazard = hazards.recordDetection(new HazardService.DetectionInput(child, device, "SWALLOW", "동전",
					"HIGH", null, OffsetDateTime.now(SERVICE_ZONE), null, null, 0.3, 0.4, null, "UNKNOWN",
					UUID.randomUUID().toString())).hazardId();
			UUID requestKey = UUID.randomUUID();
			var result = runConcurrent(() -> operations.requestRemovalCheck(hazard, requestKey));
			assertEquals(result.getFirst().actionId(), result.getLast().actionId());
			assertEquals("UNKNOWN", result.getLast().status());
			assertEquals("ACTIVE", hazards.getHazard(hazard).status());
			assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM operation_requests WHERE device_id = ?", Long.class, device));
		}
		finally { cleanupTestRegistration(key); }
	}

	private HazardService.DetectionInput input(UUID childId, String device, String event, String name, OffsetDateTime detectedAt) {
		return new HazardService.DetectionInput(childId, device, "TOY_PART", name, "HIGH", null, detectedAt,
				null, null, 0.3, 0.4, null, "UNKNOWN", event);
	}

	private <T> List<T> runConcurrent(Callable<T> operation) throws Exception {
		var executor = Executors.newFixedThreadPool(2);
		var stored = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var secondStarted = new CountDownLatch(1);
		try {
			var first = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
				T result;
				try {
					result = operation.call();
					stored.countDown();
					if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Commit gate timeout");
				}
				catch (Exception exception) {
					throw new IllegalStateException(exception);
				}
				return result;
			}));
			assertTrue(stored.await(10, TimeUnit.SECONDS), "First request should hold its transaction lock");
			var second = executor.submit(() -> {
				secondStarted.countDown();
				return operation.call();
			});
			assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
			assertThrows(TimeoutException.class, () -> second.get(300, TimeUnit.MILLISECONDS),
					"Second request must wait until the first transaction commits");
			release.countDown();
			return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
		}
		finally {
			release.countDown();
			executor.shutdown();
			if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
				executor.shutdownNow();
				assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Concurrent test requests must finish before cleanup");
			}
		}
	}

	private void cleanupTestRegistration(UUID key) {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			var ids = jdbc.queryForList("SELECT id FROM children WHERE registration_idempotency_key = ?", UUID.class, key);
			for (UUID id : ids) {
				jdbc.update("DELETE FROM device_command_delivery USING devices WHERE device_command_delivery.device_id = devices.id AND devices.child_id = ?", id);
				jdbc.update("DELETE FROM operation_requests USING devices WHERE operation_requests.device_id = devices.id AND devices.child_id = ?", id);
				jdbc.update("DELETE FROM robot_live_state USING devices WHERE robot_live_state.device_id = devices.id AND devices.child_id = ?", id);
				jdbc.update("DELETE FROM hazards WHERE child_id = ?", id);
				jdbc.update("DELETE FROM profile_history WHERE child_id = ?", id);
				jdbc.update("DELETE FROM devices WHERE child_id = ?", id);
				jdbc.update("DELETE FROM children WHERE id = ?", id);
			}
		});
	}
}
