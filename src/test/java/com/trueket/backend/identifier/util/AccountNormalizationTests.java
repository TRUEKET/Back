package com.trueket.backend.identifier.util;

import com.trueket.backend.identifier.entity.enums.IdentifierType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AccountNormalizationTests {
    @Test void fullBankNamesMatchExistingAliasesAndHashes() {
        var hashes=new IdentifierHashService("synthetic-test-secret");
        String[][] banks={{"KB국민은행","국민","KB"},{"국민은행","KB","KB"},
                {"신한은행","신한","SHINHAN"},{"우리은행","우리","WOORI"},
                {"하나은행","하나","HANA"},{"NH농협은행","농협","NH"},
                {"IBK기업은행","기업","IBK"},{"카카오뱅크","카카오","KAKAO"},{"토스뱅크","토스","TOSS"}};
        for(var bank:banks) {
            var full=IdentifierNormalizer.normalize(bank[0]+" 123-456-789012");
            var shortName=IdentifierNormalizer.normalize(bank[1]+" 123456789012");
            assertEquals(IdentifierType.ACCOUNT,full.type());
            assertEquals(bank[2]+":123456789012",full.value());
            assertEquals(shortName,full);
            assertEquals(hashes.hash(full.type(),full.value()),hashes.hash(shortName.type(),shortName.value()));
        }
    }
    @Test void missingBankMaskedNumberAndDecoratedOutputAreNotGuessed() {
        for(String input:new String[]{"123456789012","국민 1234****9012","국민 123456789012 홍길동",
                "KB:123456789012","은행미상 123456789012"}) {
            assertThrows(IllegalArgumentException.class,()->IdentifierNormalizer.normalize(input));
        }
        var phone=IdentifierNormalizer.normalize("010-1234-5678");
        assertEquals(IdentifierType.PHONE,phone.type());
    }
    @Test void leadingZerosAndDistinctBanksRemainDistinct() {
        assertEquals("KB:001234567890",IdentifierNormalizer.normalize("국민은행 001-234-567890").value());
        assertNotEquals(IdentifierNormalizer.normalize("국민 123456789012"),
                IdentifierNormalizer.normalize("신한 123456789012"));
    }
}
