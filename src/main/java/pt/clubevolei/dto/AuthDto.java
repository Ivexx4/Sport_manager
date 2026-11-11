package pt.clubevolei.dto;

public class AuthDto {
    public record LoginRequest(
            String email,
            String password,
            String tenantId
    ) {}

    public record TokenResponse(
            String accessToken,
            String tokenType,
            long expiresIn
    ) {
        public TokenResponse(String accessToken, long expiresIn) {
            this(accessToken, "Bearer", expiresIn);
        }
    }
}