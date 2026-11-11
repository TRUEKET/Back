package com.trueket.backend.user.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
@Entity @Table(name="phone_verifications", uniqueConstraints = @UniqueConstraint(columnNames = {"phone_hash", "purpose"})) @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class PhoneVerification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name="phone_hash", nullable=false) private String phoneHash;
    @Column(name="code_hash", nullable=false) private String codeHash;
    @Column(name="expires_at", nullable=false) private OffsetDateTime expiresAt;
    @Column(name="created_at", nullable=false) private OffsetDateTime sentAt;
    @Column(name="verified_at") private OffsetDateTime verifiedAt;
    @Column(name="consumed_at") private OffsetDateTime consumedAt;
    @Column(name="attempt_count", nullable=false) private int attempts;
    @Column(nullable=false) private String purpose;
    public static PhoneVerification create(String phoneHash) {
        PhoneVerification verification = new PhoneVerification();
        verification.phoneHash = phoneHash;
        verification.purpose = "SIGNUP";
        return verification;
    }
    public void issue(String codeHash, OffsetDateTime now) {
        this.codeHash = codeHash; sentAt = now; expiresAt = now.plusMinutes(3);
        verifiedAt = null; consumedAt = null; attempts = 0;
    }
    public void fail() { attempts++; }
    public void verify(OffsetDateTime now) { verifiedAt = now; }
    public void consume(OffsetDateTime now) { consumedAt = now; }
}
