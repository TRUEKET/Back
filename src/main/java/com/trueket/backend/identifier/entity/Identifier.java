package com.trueket.backend.identifier.entity;

import com.trueket.backend.identifier.entity.enums.IdentifierType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(
        name = "identifiers",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_identifiers_type_hash",
                        columnNames = {"type", "value_hash"}
                )
        }
)
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class Identifier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IdentifierType type;

    @Column(name = "value_hash", nullable = false)
    private String valueHash;

    @Column(name = "value_masked", nullable = false)
    private String valueMasked;

    @Column(name = "value_enc", nullable = false)
    private String valueEnc;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public static Identifier create(
            IdentifierType type,
            String valueHash,
            String valueMasked,
            String valueEnc
    ) {
        Identifier identifier = new Identifier();

        identifier.type = type;
        identifier.valueHash = valueHash;
        identifier.valueMasked = valueMasked;
        identifier.valueEnc = valueEnc;
        identifier.createdAt = OffsetDateTime.now();

        return identifier;
    }
    
}