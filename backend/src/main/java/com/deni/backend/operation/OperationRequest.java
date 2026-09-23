package com.deni.backend.operation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** 현재는 불변의 접수 기록이다. 기기 결과 수신 계약이 확정되기 전에는 완료로 갱신하지 않는다. */
@Entity
@Table(name = "operation_requests")
class OperationRequest {
	@Id
	private UUID id;
	@Column(name = "device_id", nullable = false, length = 100, updatable = false)
	private String deviceId;
	@Column(name = "hazard_id", updatable = false)
	private UUID hazardId;
	@Column(name = "idempotency_key", nullable = false, updatable = false)
	private UUID idempotencyKey;
	@Column(nullable = false, length = 30, updatable = false)
	private String kind;
	@Column(nullable = false, length = 20, updatable = false)
	private String status;
	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	protected OperationRequest() { }

	OperationRequest(String deviceId, UUID hazardId, UUID idempotencyKey, String kind, OffsetDateTime now) {
		this.id = UUID.randomUUID();
		this.deviceId = deviceId;
		this.hazardId = hazardId;
		this.idempotencyKey = idempotencyKey;
		this.kind = kind;
		this.status = hazardId == null ? "REQUESTED" : "UNKNOWN";
		this.createdAt = now;
	}

	boolean matches(String kind, UUID hazardId) {
		return this.kind.equals(kind) && Objects.equals(this.hazardId, hazardId);
	}
	UUID getId() { return id; }
	String getDeviceId() { return deviceId; }
	UUID getHazardId() { return hazardId; }
	String getKind() { return kind; }
	String getStatus() { return status; }
	OffsetDateTime getCreatedAt() { return createdAt; }
}
