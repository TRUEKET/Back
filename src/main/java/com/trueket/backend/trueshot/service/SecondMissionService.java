package com.trueket.backend.trueshot.service;

import com.trueket.backend.trueshot.dto.SecondMission;
import com.trueket.backend.trueshot.dto.SecondMission.*;
import org.springframework.stereotype.Service;
import java.security.SecureRandom;

@Service
public class SecondMissionService {
    private final SecureRandom random = new SecureRandom();
    public SecondMission generate() {
        Type type = Type.values()[random.nextInt(Type.values().length)];
        Direction direction = type == Type.GESTURE_POSITION
                ? Direction.values()[random.nextInt(Direction.values().length)] : null;
        return SecondMission.of(type, direction);
    }

}
