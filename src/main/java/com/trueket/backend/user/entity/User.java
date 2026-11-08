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

    @Column(name = "phone_enc", nullable = false)
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

    public void changeStatus(String status) {
        if (!status.equals("ACTIVE")
                && !status.equals("RESTRICTED")
                && !status.equals("WITHDRAWN")) {
            throw new IllegalArgumentException("올바르지 않은 회원 상태입니다.");
        }

        this.status = status;
        this.updatedAt = OffsetDateTime.now();
    }
}