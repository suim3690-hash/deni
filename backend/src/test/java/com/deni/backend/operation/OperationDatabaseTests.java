package com.deni.backend.operation;

import com.deni.backend.child.ChildService;
import com.deni.backend.common.ApiException;
import com.deni.backend.device.DeviceService;
import com.deni.backend.hazard.HazardService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = "safety.profile-refresh.enabled=false")
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
@Transactional
class OperationDatabaseTests {
	private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
	@Autowired private ChildService children;
	@Autowired private DeviceService devices;
	@Autowired private HazardService hazards;
	@Autowired private OperationService operations;
	@Autowired private EntityManager entityManager;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private com.deni.backend.device.DeviceChannel channel;
	private final java.util.Map<String, org.springframework.web.socket.WebSocketSession> sessions = new java.util.HashMap<>();
	@org.junit.jupiter.api.AfterEach void closeSessions() { sessions.forEach(channel::remove); }

	@Test
	void pausePersistsAndReplaySurvivesReloadWithoutInventingConfirmedResult() {
		String device = "operation-db-" + UUID.randomUUID();
		UUID child = setup(device);
		UUID key = UUID.randomUUID();
		var receipt = operations.requestCommand(device, "pause", key);
		entityManager.flush();
		entityManager.clear();
		assertEquals(receipt.commandId(), operations.requestCommand(device, "pause", key).commandId());
		var result = operations.getCommand(device, receipt.commandId());
		assertEquals("REQUESTED", result.status());
		assertNull(result.confirmedAt());
		assertEquals("UNKNOWN", result.deviceOperationState());
		assertEquals("QUEUED", result.deliveryState());
		assertEquals("PAUSED", devices.findStatusForChild(child).operationState());
		assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM operation_requests WHERE device_id = ?", Long.class, device));
	}

	@Test
	void removalReceiptDoesNotChangeActiveHazardAndNeverClaimsSafeCompletion() {
		String device = "operation-db-" + UUID.randomUUID();
		UUID child = setup(device);
		UUID hazard = hazards.recordDetection(new HazardService.DetectionInput(child, device, "SWALLOW", "동전", "HIGH", null,
				OffsetDateTime.now(ZONE), null, null, null, null, null, "PAUSED", UUID.randomUUID().toString())).hazardId();
		UUID key = UUID.randomUUID();
		var receipt = operations.requestRemovalCheck(hazard, key);
		entityManager.flush();
		entityManager.clear();
		assertEquals(receipt.actionId(), operations.requestRemovalCheck(hazard, key).actionId());
		var result = operations.getAction(receipt.actionId());
		assertEquals("REQUESTED", result.status());
		assertEquals("PENDING", result.treatmentStatus());
		assertNull(result.hazardPresent());
		assertNull(result.completedAt());
		assertEquals("ACTIVE", hazards.getHazard(hazard).status());
		assertEquals("IDEMPOTENCY_KEY_REUSED", assertThrows(ApiException.class,
				() -> operations.requestCommand(device, "pause", key)).getCode());
	}

	@Test
	void mismatchedHazardChildCannotCreateActionEvenIfDeviceIdMatches() {
		String device = "operation-db-" + UUID.randomUUID();
		setup(device);
		UUID otherChild = children.register("OPERATION_OTHER_CHILD_TEST", LocalDate.now(ZONE).minusMonths(20), UUID.randomUUID()).childId();
		UUID hazard = hazards.recordDetection(new HazardService.DetectionInput(otherChild, device, "SWALLOW", "동전", "HIGH", null,
				OffsetDateTime.now(ZONE), null, null, null, null, null, "PAUSED", UUID.randomUUID().toString())).hazardId();
		assertEquals("HAZARD_DEVICE_MISMATCH", assertThrows(ApiException.class,
				() -> operations.requestRemovalCheck(hazard, UUID.randomUUID())).getCode());
		assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM operation_requests WHERE device_id = ?", Long.class, device));
	}

	private UUID setup(String device) {
		UUID child = children.register("OPERATION_DATABASE_TEST", LocalDate.now(ZONE).minusMonths(20), UUID.randomUUID()).childId();
		devices.register(child, device, "요청 저장 테스트");
		var session = mock(org.springframework.web.socket.WebSocketSession.class);
		when(session.isOpen()).thenReturn(true);
		channel.register(device, session);
		sessions.put(device, session);
		devices.recordStatus(new DeviceService.StatusInput(device, "ONLINE", "PAUSED", 82, OffsetDateTime.now(ZONE).minusSeconds(1)));
		// 제거 재확인은 기기가 보고한 최신 전원·정지 상태를 요구한다.
		jdbc.update("""
				INSERT INTO robot_live_state(device_id, operation_state, movement_state, sampled_at, power_enabled, task_state)
				VALUES (?, 'PAUSED', 'STOPPED', clock_timestamp(), TRUE, 'HAZARD_PAUSED')
				""", device);
		return child;
	}

	@Test
	void databaseRejectsDuplicateDeviceKeyEvenIfServiceIsBypassed() {
		String device = "operation-db-" + UUID.randomUUID();
		setup(device);
		UUID key = UUID.randomUUID();
		operations.requestCommand(device, "pause", key);
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
				INSERT INTO operation_requests (id, device_id, idempotency_key, kind, status, created_at)
				VALUES (?, ?, ?, 'PAUSE', 'REQUESTED', CURRENT_TIMESTAMP)
				""", UUID.randomUUID(), device, key));
	}
}
