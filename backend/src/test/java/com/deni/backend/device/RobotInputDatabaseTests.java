package com.deni.backend.device;

import com.deni.backend.child.ChildService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "safety.profile-refresh.enabled=false")
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
@Transactional
class RobotInputDatabaseTests {
	@Autowired private ChildService children;
	@Autowired private DeviceService devices;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private RobotInputController api;

	private String device() {
		var child = children.register("ROBOT_INPUT_TEST", LocalDate.now().minusYears(2), UUID.randomUUID());
		String id = "robot-input-" + UUID.randomUUID();
		devices.register(child.childId(), id, "로봇 입력 테스트");
		return id;
	}

	@Test
	void imageRoundTripAndDatabaseReceiptTimeAndEventUniqueness() {
		String device = device();
		UUID event = UUID.randomUUID();
		byte[] image = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1, 2, 3};
		jdbc.update("""
				INSERT INTO detection_events(event_id, device_id, model_type, object_label, detected_at, frame_image, image_content_type)
				VALUES (?, ?, 'HAZARD', '레고', '2000-01-01T00:00:00Z', ?, 'image/jpeg')
				""", event, device, image);
		var items = api.detections(device).items();
		assertEquals(1, items.size());
		assertEquals(event, items.getFirst().eventId());
		assertTrue(items.getFirst().detectedAt().isAfter(OffsetDateTime.now().minusMinutes(1)));
		assertArrayEquals(image, api.image(device, event).getBody());
		assertThrows(com.deni.backend.common.ApiException.class, () -> api.image(device(), event));
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
				INSERT INTO detection_events(event_id, device_id, model_type, object_label, frame_image, image_content_type)
				VALUES (?, ?, 'OBJECT', '컵', ?, 'image/jpeg')
				""", event, device, image));
	}

	@Test
	void stateExpiresAndOldOrDuplicatePacketsCannotRefreshIt() {
		String device = device();
		assertTrue(api.state(device).getBody().stale());
		jdbc.update("""
				INSERT INTO robot_live_state(device_id, operation_state, movement_state, movement_duration_ms, movement_distance_m, sampled_at)
				VALUES (?, 'RELOCATING', 'FORWARD', 1200, 0.35, clock_timestamp() - INTERVAL '1 second')
				""", device);
		var live = api.state(device).getBody();
		assertFalse(live.stale());
		assertEquals("RELOCATING", live.operationState());
		assertEquals(1200L, live.movementDurationMs());
		assertEquals(0, jdbc.update("UPDATE robot_live_state SET sampled_at = sampled_at - INTERVAL '1 second', movement_state = 'BACKWARD' WHERE device_id = ?", device));
		assertEquals(0, jdbc.update("UPDATE robot_live_state SET received_at = clock_timestamp() WHERE device_id = ?", device));
		assertEquals(live.receivedAt(), api.state(device).getBody().receivedAt());
		assertEquals("FORWARD", api.state(device).getBody().movementState());
		// 다른 기기의 오래된 샘플이 처음 도착해도 최신 상태로 표시하지 않는다.
		String other = device();
		jdbc.update("INSERT INTO robot_live_state(device_id, operation_state, movement_state, sampled_at) VALUES (?, 'RUNNING', 'FORWARD', clock_timestamp() - INTERVAL '20 seconds')", other);
		assertTrue(api.state(other).getBody().stale());
		assertEquals("UNKNOWN", api.state(other).getBody().operationState());
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE robot_live_state SET movement_state = 'BACKWARD' WHERE device_id = ?", device));
	}

	@Test
	void upsertReplacesLatestRowAndRejectsFutureSample() {
		String device = device();
		String sql = """
				INSERT INTO robot_live_state(device_id, operation_state, movement_state, sampled_at)
				VALUES (?, 'RUNNING', ?, clock_timestamp() - INTERVAL '1 second')
				ON CONFLICT(device_id) DO UPDATE SET movement_state = EXCLUDED.movement_state, sampled_at = EXCLUDED.sampled_at
				""";
		assertEquals(1, jdbc.update(sql, device, "FORWARD"));
		assertEquals(1, jdbc.update(sql, device, "BACKWARD"));
		assertEquals("BACKWARD", api.state(device).getBody().movementState());
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM robot_live_state WHERE device_id = ?", Integer.class, device));
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE robot_live_state SET sampled_at = clock_timestamp() + INTERVAL '1 hour' WHERE device_id = ?", device));
	}
}
