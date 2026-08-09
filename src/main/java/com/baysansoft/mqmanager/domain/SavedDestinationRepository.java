package com.baysansoft.mqmanager.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SavedDestinationRepository extends JpaRepository<SavedDestination, Long> {

    /** Pinned first, then most recently opened — the order the chips are rendered in. */
    List<SavedDestination> findByConnectionProfileIdOrderByPinnedDescLastOpenedAtDesc(
            Long connectionProfileId);

    /** Case-sensitive by design: destination names are case-sensitive on all four providers. */
    Optional<SavedDestination> findByConnectionProfileIdAndName(Long connectionProfileId, String name);

    long countByConnectionProfileId(Long connectionProfileId);

    /** Eviction candidates, oldest first. Pinned rows are excluded and never evicted. */
    List<SavedDestination> findByConnectionProfileIdAndPinnedFalseOrderByLastOpenedAtAsc(
            Long connectionProfileId);
}
