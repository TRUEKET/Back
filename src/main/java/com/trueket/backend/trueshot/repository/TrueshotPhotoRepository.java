package com.trueket.backend.trueshot.repository;

import com.trueket.backend.trueshot.entity.TrueshotPhoto;
import com.trueket.backend.trueshot.entity.enums.TrueshotStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface TrueshotPhotoRepository extends JpaRepository<TrueshotPhoto, Long> {
    boolean existsByRequestIdAndNumber(Long requestId, Short number);

    List<TrueshotPhoto> findByRequestId(Long requestId);
    Optional<TrueshotPhoto> findByRequestIdAndNumber(Long requestId, Short number);

    @Query("select distinct photo.request.id from TrueshotPhoto photo "
            + "where photo.originalStorageKey is not null and photo.request.status = :status")
    List<Long> findRequestIdsWithOriginalsByStatus(TrueshotStatus status);
}
