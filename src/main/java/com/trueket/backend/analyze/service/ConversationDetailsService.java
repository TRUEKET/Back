package com.trueket.backend.analyze.service;

import com.trueket.backend.analyze.dto.ChatAssessment;
import com.trueket.backend.analyze.dto.ConversationDetails;
import com.trueket.backend.analyze.dto.ModelExtraction;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ConversationDetailsService {
    // Take the masked server assessment, never raw model candidates or complete source text.
    public ConversationDetails summarize(int imageCount, ModelExtraction extraction, ChatAssessment masked) {
        Map<String,Integer> sourceImages = new HashMap<>();
        Map<String,Integer> sourceOrder = new HashMap<>();
        for (int index=0;index<extraction.sentences().size();index++) {
            var sentence=extraction.sentences().get(index);
            sourceImages.put(sentence.id(),sentence.imageIndex());
            sourceOrder.put(sentence.id(),index);
        }
        Comparator<ChatAssessment.Evidence> order=Comparator
                .comparingInt((ChatAssessment.Evidence item)->sourceOrder.get(item.sentenceId()))
                .thenComparing(ChatAssessment.Evidence::strength)
                .thenComparing(ChatAssessment.Evidence::criterion)
                .thenComparing(ChatAssessment.Evidence::id);
        Map<Integer,List<ChatAssessment.Evidence>> counted=group(masked.countedSignals(),sourceImages,order);
        Map<Integer,List<ChatAssessment.Evidence>> informational=group(masked.informationalSignals(),sourceImages,order);
        Set<Integer> imageNumbers=new TreeSet<>(counted.keySet());
        imageNumbers.addAll(informational.keySet());
        List<ConversationDetails.ImageEvidence> images=imageNumbers.stream().map(number ->
                new ConversationDetails.ImageEvidence(number,counted.getOrDefault(number,List.of()),
                        informational.getOrDefault(number,List.of()))).toList();
        return new ConversationDetails(imageCount,counted.size(),images);
    }

    private Map<Integer,List<ChatAssessment.Evidence>> group(List<ChatAssessment.Evidence> signals,
                                                             Map<String,Integer> sourceImages,Comparator<ChatAssessment.Evidence> order) {
        Map<Integer,List<ChatAssessment.Evidence>> grouped=new TreeMap<>();
        signals.stream().sorted(order).forEach(signal -> grouped
                .computeIfAbsent(sourceImages.get(signal.sentenceId()),ignored->new ArrayList<>()).add(signal));
        return grouped;
    }
}