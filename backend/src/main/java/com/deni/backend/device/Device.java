package com.deni.backend.device;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "devices")
class Device {

	@Id
	@Column(length = 100)
	private String id;
	@Column(name = "child_id", nullable = false, unique = true)
	private UUID childId;
	@Column(nullable = false, length = 100)
	private String name;
	@Column(name = "connection_state", nullable = false, length = 20)
	private String connectionState;
	@Column(name = "operation_state", nullable = false, length = 30)
	private String operationState;
	@Column(name = "battery_percent")
	private Integer batteryPercent;
	@Column(name = "last_reported_at")
	private OffsetDateTime lastReportedAt;
	@Column(name = "last_seen_at")
	private OffsetDateTime lastSeenAt;
	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;
	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;
	@Version
	@Column(nullable = false)
	private long version;

	protected Device() { }

	Device(String id, UUID childId, String name, OffsetDateTime now) {
		this.id = id;
		this.childId = childId;
		this.name = name;
		this.connectionState = "UNKNOWN";
		this.operationState = "UNKNOWN";
		this.createdAt = now;
		this.updatedAt = now;
	}

	void recordStatus(String connection, String operation, Integer battery, OffsetDateTime reportedAt,
			OffsetDateTime receivedAt) {
		this.connectionState = connection;
		this.operationState = operation;
		this.batteryPercent = battery;
		this.lastReportedAt = reportedAt;
		this.lastSeenAt = receivedAt;
		this.updatedAt = receivedAt;
	}

	String getId() { return id; }
	UUID getChildId() { return childId; }
	String getName() { return name; }
	String getConnectionState() { return connectionState; }
	String getOperationState() { return operationState; }
	Integer getBatteryPercent() { return batteryPercent; }
	OffsetDateTime getLastReportedAt() { return lastReportedAt; }
	OffsetDateTime getLastSeenAt() { return lastSeenAt; }
}
