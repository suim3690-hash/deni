package com.deni.backend.hazard;

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

	@Version
	@Column(nullable = false)
	private long version;

	protected Hazard() {
	}

	Hazard(UUID id, UUID childId, String deviceId, HazardStatus status, String objectType, String objectName,
			RiskLevel riskLevel, String riskReason, OffsetDateTime detectedAt, String locationLabel,
			String mapImageUrl, Double markerX, Double markerY, String captureImageUrl,
			DeviceOperationState deviceOperationState, OffsetDateTime now) {
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
		this.createdAt = now;
		this.updatedAt = now;
	}

	UUID getId() {
		return id;
	}

	String getDeviceId() {
		return deviceId;
	}

	HazardStatus getStatus() {
		return status;
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
