package com.trueket.backend.trueshot.service;

/** Shared readability criterion for both photo rounds. */
final class TrueshotPhotoQuality {
    private TrueshotPhotoQuality() {}

    static final String INSTRUCTION = "화질 clear는 예매 내역을 알아볼 수 있는 정도인지로 판단하세요. "
            + "종이 티켓·실물 입장권·출력한 예매 내역과 기기에 표시된 예매 내역에 같은 기준을 적용하세요. "
            + "공연명·관람일시·예매번호 등 주요 정보 중 일부와 예매 내역의 구성을 식별할 수 있으면 충분합니다. "
            + "모든 작은 글자·예매번호·좌석을 정확히 읽거나 OCR로 완전히 옮길 수 있어야 하는 것은 아닙니다. "
            + "약간의 기울기·모아레·반사·손의 부분 가림이 있어도 예매 내역을 알아볼 수 있으면 clear=true입니다. "
            + "실제 흐림·반사·가림 때문에 주요 정보와 예매 내역의 구성을 알아볼 수 없을 때 clear=false입니다. ";
}
