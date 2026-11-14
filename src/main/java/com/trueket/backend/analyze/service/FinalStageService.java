package com.trueket.backend.analyze.service;

import com.trueket.backend.analyze.dto.ChatAssessment;
import com.trueket.backend.analyze.dto.FinalAssessment;
import com.trueket.backend.analyze.dto.FinalAssessment.Source;
import com.trueket.backend.analyze.dto.ReportLookup;
import com.trueket.backend.analyze.dto.Stage;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FinalStageService {
    public FinalAssessment combine(ChatAssessment chat, ReportLookup lookup) {
        Stage reportStage = lookup.targets().stream().map(ReportLookup.Target::stage)
                .reduce(Stage.SAFE, Stage::moreSevere);
        Stage finalStage = chat.stage().moreSevere(reportStage);
        boolean reportsFirst = reportStage != Stage.SAFE && reportStage.ordinal() >= chat.stage().ordinal();
        List<String> identifiers = lookup.targets().stream()
                .filter(target -> target.stage() == reportStage && reportStage != Stage.SAFE)
                .map(ReportLookup.Target::identifierId).distinct().toList();
        String warning = lookup.available() ? null
                : "신고 기록을 불러오지 못했어요. 단계는 대화만 보고 정했어요";
        return new FinalAssessment(chat, reportStage, finalStage, reportsFirst ? Source.REPORTS : Source.CHAT,
                reportStage.ordinal() > chat.stage().ordinal(), identifiers,
                chat.stage() != Stage.SAFE, finalStage != Stage.SAFE,
                reportStage == Stage.DANGER && chat.showTrueshotOnResult(), warning);
    }
}