package com.trueket.backend.trueshot.service;

import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.trueshot.dto.SecondMission;
import com.trueket.backend.trueshot.dto.SecondMission.*;
import com.trueket.backend.trueshot.entity.enums.TrueshotPhase;
import com.trueket.backend.trueshot.repository.TrueshotRequestRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class SecondMissionService {
    private final SecureRandom random = new SecureRandom();
    private final TrueshotRequestRepository requests;

    public SecondMissionService(TrueshotRequestRepository requests) { this.requests = requests; }

    public SecondMission generate() {
        Type type = Type.values()[random.nextInt(Type.values().length)];
        Direction direction = type == Type.GESTURE_POSITION
                ? Direction.values()[random.nextInt(Direction.values().length)] : null;
        return SecondMission.of(type, direction);
    }

    @Transactional(readOnly = true)
    public SecondMission current(String token) {
        var request = requests.findByToken(token).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "트루샷 링크를 찾을 수 없어요."));
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        if (request.isExpired(now) || request.secondPhotoTimedOut(now)) {
            throw new ApiException(HttpStatus.GONE, "미션 촬영 시간이 지났어요.");
        }
        if (request.getPhase() != TrueshotPhase.SECOND_WAITING || request.getMission2() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "확정된 두 번째 미션의 촬영 단계가 아니에요.");
        }
        return request.getMission2();
    }
}
