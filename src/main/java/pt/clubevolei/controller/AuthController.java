package pt.clubevolei.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pt.clubevolei.dto.AuthDto.LoginRequest;
import pt.clubevolei.dto.AuthDto.TokenResponse;
import pt.clubevolei.security.JwtTokenService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final JwtTokenService jwtTokenService;

    public AuthController(JwtTokenService jwtTokenService) {
        this.jwtTokenService = jwtTokenService;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@RequestBody LoginRequest request) {
        if (request == null || request.email() == null || request.password() == null || request.tenantId() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        if (request.tenantId().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        // TODO: Substituir por validação real na BD com PasswordEncoder no futuro
        if ("admin@clube.pt".equalsIgnoreCase(request.email().trim()) && "123456".equals(request.password())) {
            String token = jwtTokenService.generateToken(
                    "usr-12345",
                    request.email().trim(),
                    request.tenantId().trim(),
                    List.of("ROLE_ADMIN")
            );

            return ResponseEntity.ok(new TokenResponse(token, jwtTokenService.getExpirationMinutes() * 60));
        }

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
}