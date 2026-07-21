gitpackage com.plaiground.domain.session.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateSessionRequest(
    @NotBlank
    @Size(max = 8)
    @Pattern(regexp = "^[\\p{L}\\p{N}]+$")
    String nickname
) {
}
