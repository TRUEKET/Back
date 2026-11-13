package com.trueket.backend.user.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import java.sql.Types;

import java.time.OffsetDateTime;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "phone_hash", nullable = false, unique = true)
    private String phoneHash;

    @Column(name = "phone_enc", nullable = false, columnDefinition = "text")
    private String phoneEnc;

    @JdbcTypeCode(Types.CHAR)
    @Column(name = "phone_last4", length = 4, columnDefinition = "char(4)")
    private String phoneLast4;

    @Column(name = "phone_verified_at")
    private OffsetDateTime phoneVerifiedAt;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "privacy_agreed_at")
    private OffsetDateTime privacyAgreedAt;

    public void agreePrivacy(OffsetDateTime agreedAt) {
        this.privacyAgreedAt = agreedAt;
    }

    public static User create(
            String email,
            String passwordHash,
            String phoneHash,
            String phoneEnc,
            String phoneLast4,
            OffsetDateTime phoneVerifiedAt
    ) {
        User user = new User();
        user.email = email;
        user.passwordHash = passwordHash;
        user.changePhone(phoneHash, phoneEnc, phoneLast4, phoneVerifiedAt);
        user.status = "ACTIVE";
        return user;
    }

    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void changePhone(
            String phoneHash,
            String phoneEnc,
            String phoneLast4,
            OffsetDateTime phoneVerifiedAt
    ) {
        if (phoneVerifiedAt == null) {
            throw new IllegalArgumentException("휴대폰 인증이 필요합니다.");
        }
        if (phoneLast4 == null || !phoneLast4.matches("[0-9]{4}")) {
            throw new IllegalArgumentException("휴대폰 뒷자리는 숫자 4자리여야 합니다.");
        }
        this.phoneHash = phoneHash;
        this.phoneEnc = phoneEnc;
        this.phoneLast4 = phoneLast4;
        this.phoneVerifiedAt = phoneVerifiedAt;
    }

    @PrePersist
    private void initializeTimestamps() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    private void updateTimestamp() {
        this.updatedAt = OffsetDateTime.now();
    }

    public void changeStatus(String status) {
        if (!"ACTIVE".equals(status)
                && !"RESTRICTED".equals(status)
                && !"WITHDRAWN".equals(status)) {
            throw new IllegalArgumentException("올바르지 않은 회원 상태입니다.");
        }

        this.status = status;
        this.updatedAt = OffsetDateTime.now();
    }
}