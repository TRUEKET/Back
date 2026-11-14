package com.trueket.backend.trueshot.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.trueket.backend.trueshot.entity.enums.MaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "trueshot_photos", uniqueConstraints =
        @UniqueConstraint(name = "uq_trueshot_photos_request_n", columnNames = {"request_id", "n"}))
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class TrueshotPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private TrueshotRequest request;

    @Column(name = "n", nullable = false)
    private Short number;

    // 비공개 Storage의 가린 사진 경로. 가림 대기 또는 실패 시 null이다.
    @Column(name = "masked_image")
    private String maskedImage;

    @Enumerated(EnumType.STRING)
    @Column(name = "mask_status", nullable = false)
    private MaskStatus maskStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "mask_boxes", columnDefinition = "jsonb", nullable = false)
    private JsonNode maskBoxes;

    // 비공개 임시 원본 경로. 최종 판정 또는 실패 후 원본을 삭제한다.
    @Column(name = "original_storage_key")
    private String originalStorageKey;

    @Column(name = "original_deleted_at")
    private OffsetDateTime originalDeletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public static TrueshotPhoto create(TrueshotRequest request, short number, OffsetDateTime now) {
        if (number != 1 && number != 2) {
            throw new IllegalArgumentException("트루샷 사진 번호는 1 또는 2여야 합니다.");
        }
        TrueshotPhoto photo = new TrueshotPhoto();
        photo.request = request;
        photo.number = number;
        photo.maskStatus = MaskStatus.PENDING;
        photo.maskBoxes = JsonNodeFactory.instance.arrayNode();
        photo.createdAt = now;
        return photo;
    }

    public void storeOriginal(String key) {
        originalStorageKey = key;
    }

    public void markOriginalDeleted(OffsetDateTime now) {
        originalStorageKey = null;
        originalDeletedAt = now;
    }

    public void maskingReady(String key, JsonNode boxes) {
        maskedImage=key; maskBoxes=boxes; maskStatus=MaskStatus.READY;
    }

    public void maskingFailed() {
        maskedImage=null; maskStatus=MaskStatus.FAILED;
    }
}
