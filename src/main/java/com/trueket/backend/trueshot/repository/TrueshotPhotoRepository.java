package com.trueket.backend.trueshot.repository;

import com.trueket.backend.trueshot.entity.TrueshotPhoto;
import com.trueket.backend.trueshot.entity.enums.TrueshotStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface TrueshotPhotoRepository extends JpaRepository<TrueshotPhoto, Long> {
    boolean existsByRequestIdAndNumber(Long requestId, Short number);

    List<TrueshotPhoto> findByRequestId(Long requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select photo from TrueshotPhoto photo where photo.request.id = :requestId order by photo.id")
    List<TrueshotPhoto> findByRequestIdForUpdate(Long requestId);
    Optional<TrueshotPhoto> findByRequestIdAndNumber(Long requestId, Short number);

    @Query("select distinct photo.request.id from TrueshotPhoto photo "
            + "where photo.originalStorageKey is not null and photo.request.status = :status")
    List<Long> findRequestIdsWithOriginalsByStatus(TrueshotStatus status);
}
