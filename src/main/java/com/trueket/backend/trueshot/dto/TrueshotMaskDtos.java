package com.trueket.backend.trueshot.dto;

import com.trueket.backend.trueshot.entity.enums.MaskStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;

public final class TrueshotMaskDtos {
    private TrueshotMaskDtos() {}

    // 화면 크기와 관계없이 같은 영역을 가리도록 0~1 사이의 비율을 사용한다.
    public record Box(@DecimalMin("0") @DecimalMax("1") double x,
                      @DecimalMin("0") @DecimalMax("1") double y,
                      @DecimalMin(value="0", inclusive=false) @DecimalMax("1") double width,
                      @DecimalMin(value="0", inclusive=false) @DecimalMax("1") double height) {
        public boolean valid() {
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(width) && Double.isFinite(height)
                    && x >= 0 && y >= 0 && width > 0 && height > 0 && x + width <= 1 && y + height <= 1;
        }
    }

    public record MaskResult(MaskStatus maskStatus, String previewPath, String message) {}
    public record ConfirmFirst(@NotNull @Size(max=300) List<@NotNull @Valid Box> boxes) {}
    public record SecondInstruction(String instruction2, OffsetDateTime deadline2, OffsetDateTime expiresAt) {}
}
