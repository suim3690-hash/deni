package com.deni.backend.hazard;

import com.deni.backend.common.IdempotencyGuard;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "hazards")
class Hazard {

	@Id
	private UUID id;

	@Column(name = "child_id", nullable = false)
	private UUID childId;

	@Column(name = "device_id", nullable = false, length = 100)
	private String deviceId;

	@Column(name = "source_event_id", length = 100, updatable = false)
	private String sourceEventId;

	@Column(name = "detection_input_hash", length = 64, updatable = false)
	private String detectionInputHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private HazardStatus status;

	@Column(name = "object_type", nullable = false, length = 50)
	private String objectType;

	@Column(name = "object_name", nullable = false, length = 100)
	private String objectName;

	@Enumerated(EnumType.STRING)
	@Column(name = "risk_level", nullable = false, length = 20)
	private RiskLevel riskLevel;

	@Column(name = "risk_reason", columnDefinition = "TEXT")
	private String riskReason;

	@Column(name = "detected_at", nullable = false)
	private OffsetDateTime detectedAt;

	@Column(name = "location_label", length = 200)
	private String locationLabel;

	@Column(name = "map_image_url", columnDefinition = "TEXT")
	private String mapImageUrl;

	@Column(name = "marker_x")
	private Double markerX;

	@Column(name = "marker_y")
	private Double markerY;

	@Column(name = "capture_image_url", columnDefinition = "TEXT")
	private String captureImageUrl;

	@Enumerated(EnumType.STRING)
	@Column(name = "device_operation_state", nullable = false, length = 30)
	private DeviceOperationState deviceOperationState;

	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	@Column(name = "acknowledged_at")
	private OffsetDateTime acknowledgedAt;

	@Version
	@Column(nullable = false)
	private long version;

	protected Hazard() {
	}

	Hazard(UUID id, UUID childId, String deviceId, HazardStatus status, String objectType, String objectName,
			RiskLevel riskLevel, String riskReason, OffsetDateTime detectedAt, String locationLabel,
			String mapImageUrl, Double markerX, Double markerY, String captureImageUrl,
			DeviceOperationState deviceOperationState, String sourceEventId, OffsetDateTime now) {
		this.id = id;
		this.childId = childId;
		this.deviceId = deviceId;
		this.status = status;
		this.objectType = objectType;
		this.objectName = objectName;
		this.riskLevel = riskLevel;
		this.riskReason = riskReason;
		this.detectedAt = detectedAt;
		this.locationLabel = locationLabel;
		this.mapImageUrl = mapImageUrl;
		this.markerX = markerX;
		this.markerY = markerY;
		this.captureImageUrl = captureImageUrl;
		this.deviceOperationState = deviceOperationState;
		this.sourceEventId = sourceEventId;
		this.detectionInputHash = sourceEventId == null ? null : IdempotencyGuard.fingerprint(
				childId.toString(), deviceId, objectType, objectName, riskLevel.name(), riskReason,
				detectedAt.toInstant().toString(), locationLabel, mapImageUrl,
				markerX == null ? null : markerX.toString(), markerY == null ? null : markerY.toString(),
				captureImageUrl, deviceOperationState.name());
		this.createdAt = now;
		this.updatedAt = now;
	}

	/** 같은 물체가 계속 보일 때 ACTIVE 건을 갱신한다. 새 행은 만들지 않는다. */
	void refreshFromDetection(OffsetDateTime detectedAt, String captureImageUrl, RiskLevel riskLevel,
			DeviceOperationState deviceOperationState, OffsetDateTime now) {
		if (detectedAt != null && detectedAt.isAfter(this.detectedAt)) {
			this.detectedAt = detectedAt;
			// 사진과 감지 시각은 한 이벤트의 값이어야 한다. 새 이벤트에 사진이
			// 없으면 이전 사진을 보여주지 않고 null로 둔다.
			this.captureImageUrl = captureImageUrl;
			this.riskLevel = riskLevel;
			if (deviceOperationState != null) {
				this.deviceOperationState = deviceOperationState;
			}
			this.updatedAt = now;
		}
	}

	void acknowledgeLiving(OffsetDateTime now) {
		if (acknowledgedAt == null) {
			acknowledgedAt = now;
			updatedAt = now;
		}
	}

	UUID getId() {
		return id;
	}

	UUID getChildId() {
		return childId;
	}

	String getDeviceId() {
		return deviceId;
	}

	String getDetectionInputHash() {
		return detectionInputHash;
	}

	HazardStatus getStatus() {
		return status;
	}

	OffsetDateTime getAcknowledgedAt() {
		return acknowledgedAt;
	}

	String getObjectType() {
		return objectType;
	}

	String getObjectName() {
		return objectName;
	}

	RiskLevel getRiskLevel() {
		return riskLevel;
	}

	String getRiskReason() {
		return riskReason;
	}

	OffsetDateTime getDetectedAt() {
		return detectedAt;
	}

	String getLocationLabel() {
		return locationLabel;
	}

	String getMapImageUrl() {
		return mapImageUrl;
	}

	Double getMarkerX() {
		return markerX;
	}

	Double getMarkerY() {
		return markerY;
	}

	String getCaptureImageUrl() {
		return captureImageUrl;
	}

	DeviceOperationState getDeviceOperationState() {
		return deviceOperationState;
	}
}

enum HazardStatus {
	ACTIVE,
	RESOLVED
}

enum RiskLevel {
	VERY_HIGH,
	HIGH,
	MEDIUM,
	LOW
}

enum DeviceOperationState {
	RUNNING,
	PAUSED,
	STOPPING,
	RESUMING,
	READY_TO_RESUME,
	UNKNOWN
}
