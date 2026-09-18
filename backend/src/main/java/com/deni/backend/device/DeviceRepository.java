package com.deni.backend.device;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface DeviceRepository extends JpaRepository<Device, String> {
	Optional<Device> findByChildId(UUID childId);
}
