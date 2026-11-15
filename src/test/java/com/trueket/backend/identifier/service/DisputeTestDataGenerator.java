package com.trueket.backend.identifier.service;

import com.trueket.backend.identifier.util.IdentifierHashService;
import com.trueket.backend.identifier.util.IdentifierNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("seed-dispute-test")
@RequiredArgsConstructor
public class DisputeTestDataGenerator implements CommandLineRunner {

    private final IdentifierHashService hashService;
    private final IdentifierEncryptionService encryptionService;

    @Override
    public void run(String... args) {
        String input = "신한 999-999-9-999999";

        var normalized = IdentifierNormalizer.normalize(input);

        String hash = hashService.hash(
                normalized.type(),
                normalized.value()
        );

        String encrypted = encryptionService.encrypt(
                normalized.value()
        );

        System.out.println("========== RP-08 TEST ==========");
        System.out.println("NORMALIZED=" + normalized.value());
        System.out.println("VALUE_HASH=" + hash);
        System.out.println("VALUE_ENC=" + encrypted);
        System.out.println("================================");
    }
}