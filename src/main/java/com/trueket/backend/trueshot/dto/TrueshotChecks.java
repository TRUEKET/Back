package com.trueket.backend.trueshot.dto;

import com.trueket.backend.trueshot.entity.TrueshotRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

public final class TrueshotChecks {
    private TrueshotChecks() {}
    public enum Code { PHOTO_QUALITY, AUTH_CODE, MISSION }
    public enum Outcome { PASSED, FAILED, PENDING, NOT_CHECKED }
    public record Check(int photoNumber, Code code, Outcome status, String message) {}

    public static List<Check> first(boolean clear, boolean codeVisible, boolean codeMatches) {
        boolean code=codeVisible && codeMatches;
        return List.of(quality(1,clear),new Check(1,Code.AUTH_CODE,code?Outcome.PASSED:Outcome.FAILED,
                code?"인증코드가 일치해요.":!codeVisible?"인증코드가 잘 보이지 않아요.":"인증코드가 일치하지 않아요."));
    }

    public static List<Check> pendingSecond() {
        return List.of(pending(2,Code.PHOTO_QUALITY),pending(2,Code.MISSION));
    }

    public static List<Check> result(TrueshotRequest request,boolean finished) {
        var checks=new ArrayList<Check>();
        if (request.getFirstVerifiedAt()!=null) checks.addAll(first(true,true,true));
        else {
            // 재촬영으로 거절된 첫 사진은 저장하지 않으므로 이후 조회에서 결과를 추정하지 않는다.
            checks.add(unknown(1,Code.PHOTO_QUALITY));
            checks.add(unknown(1,Code.AUTH_CODE));
        }
        JsonNode snapshot=request.getJudgmentResult();
        if (request.getJudgedAt()!=null && snapshot!=null) {
            var clear=snapshot.path("secondPhotoClear");
            checks.add(clear.isBoolean()?quality(2,clear.asBoolean()):unknown(2,Code.PHOTO_QUALITY));
            String mission=snapshot.path("missionCheck").path("status").asText();
            checks.add(switch (mission) {
                case "MATCHED" -> new Check(2,Code.MISSION,Outcome.PASSED,"촬영 미션을 지켰어요.");
                case "NOT_MATCHED" -> new Check(2,Code.MISSION,Outcome.FAILED,"손이 없거나 손 모양·위치가 지시와 일치하지 않아요.");
                default -> unknown(2,Code.MISSION);
            });
        } else if (!finished && request.getSecondReceivedAt()!=null) checks.addAll(pendingSecond());
        else {
            checks.add(unknown(2,Code.PHOTO_QUALITY));
            checks.add(unknown(2,Code.MISSION));
        }
        return List.copyOf(checks);
    }

    private static Check quality(int number,boolean clear) {
        return new Check(number,Code.PHOTO_QUALITY,clear?Outcome.PASSED:Outcome.FAILED,
                clear?"내용을 알아볼 수 있는 화질이에요.":"사진이 흐리거나 내용이 잘 보이지 않아요.");
    }
    private static Check pending(int number,Code code) { return new Check(number,code,Outcome.PENDING,"사진을 확인하고 있어요."); }
    private static Check unknown(int number,Code code) { return new Check(number,code,Outcome.NOT_CHECKED,"확인된 결과가 없어요."); }
}
