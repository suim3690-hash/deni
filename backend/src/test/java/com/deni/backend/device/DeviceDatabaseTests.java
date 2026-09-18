package com.deni.backend.device;

import com.deni.backend.child.ChildService;
import com.deni.backend.child.DashboardController;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = "safety.profile-refresh.enabled=false")
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
@Transactional
class DeviceDatabaseTests {
	@Autowired private ChildService children;
	@Autowired private DeviceService devices;
	@Autowired private EntityManager entityManager;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private DashboardController dashboard;

	@Test
	void registrationAndTelemetryPersistAndAppearInDashboardAfterReload() {
		UUID child = child();
		String id = "device-db-" + UUID.randomUUID();
		devices.register(child, id, "기기 저장 테스트");
		entityManager.flush();
		entityManager.clear();
		assertEquals("UNKNOWN", devices.findStatusForChild(child).connectionState());
		assertEquals(id, devices.register(child, id, "기기 저장 테스트").deviceId());
		assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE child_id = ?", Long.class, child));
		OffsetDateTime at = OffsetDateTime.now(ZoneId.of("Asia/Seoul")).minusSeconds(1);
		devices.recordStatus(new DeviceService.StatusInput(id, "ONLINE", "PAUSED", 82, at));
		entityManager.flush();
		entityManager.clear();
		var response = dashboard.getDashboard(child);
		assertEquals(id, response.device().deviceId());
		assertEquals("기기 저장 테스트", response.device().name());
		assertEquals("PAUSED", response.device().operationState());
		assertEquals(82, response.device().batteryPercent());
		assertFalse(response.device().commandsAvailable());
		assertNotNull(response.device().lastSeenAt());
		var lastSeen = response.device().lastSeenAt();
		devices.recordStatus(new DeviceService.StatusInput(id, "ONLINE", "PAUSED", 82, at));
		assertEquals(lastSeen.toInstant(), devices.getStatus(id).lastSeenAt().toInstant());
	}

	@Test
	void databaseRejectsMultipleDevicesForSameChildEvenIfServiceIsBypassed() {
		UUID child = child();
		String id = "device-db-" + UUID.randomUUID();
		devices.register(child, id, "중복 테스트");
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
				INSERT INTO devices (id, child_id, name, created_at, updated_at)
				VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", "device-db-" + UUID.randomUUID(), child, "중복 테스트"));
	}

	private UUID child() {
		return children.register("DEVICE_DATABASE_TEST", LocalDate.now(ZoneId.of("Asia/Seoul")).minusMonths(20), UUID.randomUUID()).childId();
	}
}
