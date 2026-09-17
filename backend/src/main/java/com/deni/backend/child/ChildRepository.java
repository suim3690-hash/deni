package com.deni.backend.child;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface ChildRepository extends JpaRepository<Child, UUID> {

	Optional<Child> findByRegistrationIdempotencyKey(UUID registrationIdempotencyKey);
}
