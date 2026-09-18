package com.deni.backend.child;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ProfileHistoryRepository extends JpaRepository<ProfileHistory, Long> {

	Optional<ProfileHistory> findTopByChildIdOrderByChangedAtDescIdDesc(UUID childId);

	List<ProfileHistory> findByChildIdAndChangedAtGreaterThanEqualAndChangedAtLessThanOrderByChangedAtAscIdAsc(
			UUID childId, OffsetDateTime start, OffsetDateTime end);
}
