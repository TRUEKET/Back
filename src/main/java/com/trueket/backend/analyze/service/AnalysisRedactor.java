package com.trueket.backend.analyze.service;

import com.trueket.backend.analyze.dto.ChatAssessment;
import com.trueket.backend.analyze.dto.ChatAssessment.Evidence;
import com.trueket.backend.analyze.dto.ModelExtraction;
import com.trueket.backend.analyze.dto.AnalyzeResponse.MaskedIdentifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class AnalysisRedactor {
    public ChatAssessment redact(ChatAssessment chat, ModelExtraction result) {
        return new ChatAssessment(chat.stage(), evidence(chat.countedSignals(), result),
                evidence(chat.informationalSignals(), result), chat.criteria(), chat.accountNoticeRequired(),
                chat.showTrueshotOnResult(), chat.dictionaryVersion());
    }

    public String text(String text, ModelExtraction result) {
        if (text == null) return null;
        List<String> sensitive = new ArrayList<>(result.sensitiveValues());
        sensitive.addAll(result.extracted().accounts());
        sensitive.addAll(result.extracted().phones());
        sensitive.addAll(result.extracted().xAccounts());
        sensitive.addAll(result.extracted().links());
        sensitive.sort(Comparator.comparingInt(String::length).reversed());
        String masked = text;
        for (String value : sensitive) {
            masked = masked.replace(value, "***");
        }
        return masked;
    }

    public List<MaskedIdentifier> identifiers(ModelExtraction result) {
        List<MaskedIdentifier> masked = new ArrayList<>();
        result.extracted().accounts().forEach(value -> masked.add(new MaskedIdentifier("ACCOUNT", mask(value))));
        result.extracted().phones().forEach(value -> masked.add(new MaskedIdentifier("PHONE", mask(value))));
        result.extracted().xAccounts().forEach(value -> masked.add(new MaskedIdentifier("SNS", mask(value))));
        return List.copyOf(masked);
    }

    private String mask(String value) {
        return value.length() <= 4 ? "***" : "***" + value.substring(value.length() - 4);
    }

    private List<Evidence> evidence(List<Evidence> items, ModelExtraction result) {
        return items.stream().map(item -> new Evidence(item.id(), item.criterion(), item.strength(),
                item.sentenceId(), text(item.quote(), result), text(item.reason(), result))).toList();
    }
}