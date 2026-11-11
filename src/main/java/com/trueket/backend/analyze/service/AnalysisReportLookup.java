package com.trueket.backend.analyze.service;

import com.trueket.backend.analyze.dto.ModelExtraction.Extracted;
import com.trueket.backend.analyze.dto.ReportLookup;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AnalysisReportLookup {
    public ReportLookup lookup(Extracted identifiers) {
        boolean hasTargets = !identifiers.accounts().isEmpty() || !identifiers.phones().isEmpty()
                || !identifiers.xAccounts().isEmpty();
        return new ReportLookup(!hasTargets, List.of());
    }
}
