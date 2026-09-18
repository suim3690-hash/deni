package com.deni.backend.operation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface OperationRequestRepository extends JpaRepository<OperationRequest, UUID> {
	Optional<OperationRequest> findByDeviceIdAndIdempotencyKey(String deviceId, UUID idempotencyKey);
}
