package com.trueket.backend.trueshot.service;

import com.trueket.backend.trueshot.dto.SecondMission;
import com.trueket.backend.trueshot.dto.SecondMission.*;
import org.springframework.stereotype.Service;
import java.security.SecureRandom;

@Service
public class SecondMissionService {
    // 가리키기는 손 인식 누락이 많아 신규 발급에서 제외한다.
    private static final Type[] ACTIVE_TYPES = {Type.GESTURE, Type.GESTURE_POSITION};
    private final SecureRandom random = new SecureRandom();
    public SecondMission generate() {
        Type type = ACTIVE_TYPES[random.nextInt(ACTIVE_TYPES.length)];
        Direction direction = type == Type.GESTURE_POSITION
                ? Direction.values()[random.nextInt(Direction.values().length)] : null;
        return SecondMission.of(type, direction);
    }

}
