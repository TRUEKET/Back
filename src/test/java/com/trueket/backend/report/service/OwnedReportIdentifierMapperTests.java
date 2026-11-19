package com.trueket.backend.report.service;

import com.trueket.backend.identifier.entity.Identifier;
import com.trueket.backend.identifier.entity.enums.IdentifierType;
import com.trueket.backend.identifier.service.IdentifierEncryptionService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OwnedReportIdentifierMapperTests {
    private final IdentifierEncryptionService encryption=new IdentifierEncryptionService("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    private final OwnedReportIdentifierMapper mapper=new OwnedReportIdentifierMapper(encryption);
    private String display(IdentifierType type,String raw) {
        return mapper.map(Identifier.create(type,"synthetic-hash","***",encryption.encrypt(raw))).value();
    }
    @Test void normalizedAndLegacyBankFormatsRemainComplete() {
        assertEquals("신한 11022123456",display(IdentifierType.ACCOUNT,"SHINHAN:11022123456"));
        assertEquals("신한 11022123456",display(IdentifierType.ACCOUNT,"신한은행 110-22-123456"));
        assertEquals("010-1234-5678",display(IdentifierType.PHONE,"01012345678"));
        assertEquals("011-123-4567",display(IdentifierType.PHONE,"0111234567"));
        assertEquals("@seller",display(IdentifierType.SNS,"Seller"));
    }
    @Test void invalidTypeOrCiphertextNeverTurnsIntoPretendOriginalValue() {
        assertThrows(com.trueket.backend.global.exception.ApiException.class,()->display(IdentifierType.PHONE,"SHINHAN:11022123456"));
        assertThrows(com.trueket.backend.global.exception.ApiException.class,()->mapper.map(
                Identifier.create(IdentifierType.ACCOUNT,"synthetic","***","corrupt")));
    }
}
