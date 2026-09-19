package com.deni.backend.device;

import com.deni.backend.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 외부 PC가 저장한 원본을 읽는다. 원본 보고를 위험 해결·명령 성공으로 해석하지 않는다. */
@RestController
@RequestMapping("/api/v1/devices/{deviceId}")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"})
public class RobotInputController {
	private final JdbcTemplate jdbc;
	private final DeviceService devices;

	public RobotInputController(JdbcTemplate jdbc, DeviceService devices) {
		this.jdbc = jdbc;
		this.devices = devices;
	}

	@GetMapping("/robot-state")
	public ResponseEntity<LiveState> state(@PathVariable String deviceId) {
		devices.getStatus(deviceId);
		var rows = jdbc.query("""
				SELECT *, sampled_at <= clock_timestamp() AND received_at <= clock_timestamp()
				    AND sampled_at > clock_timestamp() - INTERVAL '10 seconds'
				    AND received_at > clock_timestamp() - INTERVAL '10 seconds' AS fresh
				FROM robot_live_state WHERE device_id = ?
				""", (rs, row) -> {
			boolean fresh = rs.getBoolean("fresh");
			return new LiveState(deviceId, fresh ? rs.getString("operation_state") : "UNKNOWN",
					fresh ? rs.getString("movement_state") : "UNKNOWN",
					fresh ? rs.getObject("movement_duration_ms", Long.class) : null,
					fresh ? rs.getBigDecimal("movement_distance_m") : null,
					rs.getObject("sampled_at", OffsetDateTime.class), rs.getObject("received_at", OffsetDateTime.class), !fresh);
		}, deviceId);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(rows.isEmpty()
				? new LiveState(deviceId, "UNKNOWN", "UNKNOWN", null, null, null, null, true) : rows.getFirst());
	}

	@GetMapping("/detections")
	public DetectionList detections(@PathVariable String deviceId) {
		devices.getStatus(deviceId);
		var rows = jdbc.query("""
				SELECT event_id, model_type, object_label, detected_at FROM detection_events
				WHERE device_id = ? ORDER BY detected_at DESC, event_id DESC LIMIT 50
				""", (rs, row) -> new Detection(rs.getObject("event_id", UUID.class), rs.getString("model_type"),
				rs.getString("object_label"), rs.getObject("detected_at", OffsetDateTime.class),
				"/api/v1/devices/" + deviceId + "/detections/" + rs.getString("event_id") + "/image"), deviceId);
		return new DetectionList(rows);
	}

	@GetMapping("/detections/{eventId}/image")
	public ResponseEntity<byte[]> image(@PathVariable String deviceId, @PathVariable UUID eventId) {
		devices.getStatus(deviceId);
		var rows = jdbc.query("SELECT frame_image, image_content_type FROM detection_events WHERE device_id = ? AND event_id = ?",
				(rs, row) -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
						.header("X-Content-Type-Options", "nosniff")
						.contentType(MediaType.parseMediaType(rs.getString("image_content_type")))
						.body(rs.getBytes("frame_image")), deviceId, eventId);
		if (rows.isEmpty()) throw ApiException.notFound("DETECTION_NOT_FOUND", "탐지 이미지를 찾을 수 없습니다.");
		return rows.getFirst();
	}

	public record LiveState(String deviceId, String operationState, String movementState, Long movementDurationMs,
			java.math.BigDecimal movementDistanceM, OffsetDateTime sampledAt, OffsetDateTime receivedAt, boolean stale) { }
	public record Detection(UUID eventId, String modelType, String objectLabel, OffsetDateTime detectedAt, String imageUrl) { }
	public record DetectionList(List<Detection> items) { }
}
