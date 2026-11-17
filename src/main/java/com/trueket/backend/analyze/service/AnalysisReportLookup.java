package com.trueket.backend.analyze.service;

import com.trueket.backend.analyze.dto.ModelExtraction.Extracted;
import com.trueket.backend.analyze.dto.ReportLookup;
import com.trueket.backend.identifier.entity.enums.IdentifierType;
import com.trueket.backend.identifier.repository.IdentifierRepository;
import com.trueket.backend.identifier.util.IdentifierHashService;
import com.trueket.backend.identifier.util.IdentifierNormalizer;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class AnalysisReportLookup {
    private final IdentifierRepository identifiers;
    private final IdentifierHashService hashes;
    private final PublicReportCounts counts;

    public AnalysisReportLookup(IdentifierRepository identifiers, IdentifierHashService hashes, PublicReportCounts counts) {
        this.identifiers = identifiers;
        this.hashes = hashes;
        this.counts = counts;
    }

    public ReportLookup lookup(Extracted extracted) {
        Set<Long> ids = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        boolean complete = true;
        try {
            for (String value : extracted.accounts()) complete &= match(value, IdentifierType.ACCOUNT, ids, seen);
            for (String value : extracted.phones()) complete &= match(value, IdentifierType.PHONE, ids, seen);
            for (String value : extracted.xAccounts()) {
                String sns = value == null ? null : value.trim();
                if (sns != null && !sns.startsWith("@")) sns = "@" + sns;
                complete &= match(sns, IdentifierType.SNS, ids, seen);
            }
            return new ReportLookup(complete, counts.count(ids, OffsetDateTime.now()));
        } catch (DataAccessException e) {
            // Do not expose raw identifiers, hashes or SQL errors to the response/logs.
            return new ReportLookup(false, List.of());
        }
    }

    private boolean match(String value, IdentifierType expected, Set<Long> ids, Set<String> seen) {
        IdentifierNormalizer.NormalizedIdentifier normalized;
        try { normalized = IdentifierNormalizer.normalize(value); }
        catch (IllegalArgumentException e) { return false; }
        if (normalized.type() != expected) return false;
        String hash = hashes.hash(normalized.type(), normalized.value());
        if (seen.add(expected.name() + ":" + hash)) {
            identifiers.findByTypeAndValueHash(expected, hash).ifPresent(identifier -> ids.add(identifier.getId()));
        }
        return true;
    }
}
