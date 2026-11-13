package com.trueket.backend.trueshot.dto;

import com.trueket.backend.trueshot.entity.enums.TrueshotPhase;

import java.time.OffsetDateTime;
import java.util.List;

public record TrueshotPhotoResponse(int number, TrueshotPhase phase, boolean accepted,
                                    OffsetDateTime deadline, List<String> retryReasons) {}
