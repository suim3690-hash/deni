package com.deni.backend.child;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

interface ChildRepository extends JpaRepository<Child, UUID> {

	Optional<Child> findByRegistrationIdempotencyKey(UUID registrationIdempotencyKey);

	@Query("SELECT c.id FROM Child c ORDER BY c.id")
	Slice<UUID> findIdsForProfileRefresh(Pageable pageable);
}
