package com.baysansoft.mqmanager.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SavedRequestRepository extends JpaRepository<SavedRequest, Long> {

    /** Everything remembered for one destination, newest first — named and history together. */
    List<SavedRequest> findByConnectionProfileIdAndDestinationNameOrderByCreatedAtDesc(
            Long connectionProfileId, String destinationName);

    /** A named request, for the upsert on save. */
    Optional<SavedRequest> findByConnectionProfileIdAndDestinationNameAndLabel(
            Long connectionProfileId, String destinationName, String label);

    /** History entries only (no label), oldest first — the eviction candidates. */
    List<SavedRequest> findByConnectionProfileIdAndDestinationNameAndLabelIsNullOrderByCreatedAtAsc(
            Long connectionProfileId, String destinationName);

    long countByConnectionProfileIdAndDestinationNameAndLabelIsNull(
            Long connectionProfileId, String destinationName);
}
