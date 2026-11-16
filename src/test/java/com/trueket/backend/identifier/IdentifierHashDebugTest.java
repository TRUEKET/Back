package com.trueket.backend.identifier;

import com.trueket.backend.identifier.entity.Identifier;
import com.trueket.backend.identifier.repository.IdentifierRepository;
import com.trueket.backend.identifier.service.IdentifierEncryptionService;
import com.trueket.backend.identifier.util.IdentifierHashService;
import com.trueket.backend.identifier.util.IdentifierNormalizer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class IdentifierHashDebugTest {
    @Autowired IdentifierRepository identifiers;
    @Autowired IdentifierEncryptionService encryption;
    @Autowired IdentifierHashService hashes;

    @Test void verifyStoredIdentifierHash() {
        var normalized = IdentifierNormalizer.normalize("신한 111-111-111111");
        var stored = identifiers.saveAndFlush(Identifier.create(normalized.type(),
                hashes.hash(normalized.type(), normalized.value()), "***1111",
                encryption.encrypt(normalized.value())));
        var loaded = identifiers.findById(stored.getId()).orElseThrow();
        String decrypted = encryption.decrypt(loaded.getValueEnc());
        assertThat(decrypted).isEqualTo(normalized.value());
        assertThat(hashes.hash(loaded.getType(), decrypted)).isEqualTo(loaded.getValueHash());
        var lookup = IdentifierNormalizer.normalize("신한은행 111111111111");
        assertThat(hashes.hash(lookup.type(), lookup.value())).isEqualTo(loaded.getValueHash());
        var different = IdentifierNormalizer.normalize("신한 222-222-222222");
        assertThat(hashes.hash(different.type(), different.value())).isNotEqualTo(loaded.getValueHash());
    }
}
