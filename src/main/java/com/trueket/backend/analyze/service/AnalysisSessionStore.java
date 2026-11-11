package com.trueket.backend.analyze.service;

import com.trueket.backend.analyze.dto.ModelExtraction;
import com.trueket.backend.global.ai.GeminiClient.Image;
import com.trueket.backend.global.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class AnalysisSessionStore {
    public record Snapshot(ModelExtraction extraction, List<Image> captures) {
        public Snapshot { captures = List.copyOf(captures); }
    }
    private record Entry(UUID owner, Snapshot snapshot, Instant expiresAt, long size) {}
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private long bytes;

    public synchronized UUID save(UUID owner, Snapshot snapshot) {
        purge();
        var iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.owner().equals(owner)) {
                bytes -= entry.size();
                iterator.remove();
            }
        }
        long size = snapshot.captures().stream().mapToLong(image -> image.data().length() * 2L).sum();
        while (!entries.isEmpty() && (entries.size() >= 16 || bytes + size > 64 * 1024 * 1024)) {
            UUID oldest = entries.keySet().iterator().next();
            bytes -= entries.remove(oldest).size();
        }
        UUID id = UUID.randomUUID();
        entries.put(id, new Entry(owner, snapshot, Instant.now().plusSeconds(900), size));
        bytes += size;
        return id;
    }

    public synchronized Snapshot get(UUID id, UUID owner) {
        purge();
        Entry entry = entries.get(id);
        if (entry == null || !entry.owner().equals(owner)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "판독 세션이 끝났어요. 다시 판독해 주세요");
        }
        return entry.snapshot();
    }

    public synchronized void delete(UUID id, UUID owner) {
        get(id, owner);
        bytes -= entries.remove(id).size();
    }

    @Scheduled(fixedDelay = 60000)
    public synchronized void purge() {
        Instant now = Instant.now();
        var iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (!entry.expiresAt().isAfter(now)) {
                bytes -= entry.size();
                iterator.remove();
            }
        }
    }
}
