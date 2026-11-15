package pt.voleibol.gestao.athletes;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import pt.voleibol.gestao.shared.Auth;

@RestController
@RequestMapping("/api/v1")
class DocumentController {

    record DocumentRequest(@NotBlank String type, String fileName, String fileUrl, LocalDate validUntil) {
    }

    private final DocumentService service;

    DocumentController(DocumentService service) {
        this.service = service;
    }

    @GetMapping("/athletes/{athleteId}/documents")
    List<DocumentService.DocumentView> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID athleteId) {
        return service.list(Auth.actor(jwt), Auth.roles(jwt), athleteId);
    }

    @PostMapping("/athletes/{athleteId}/documents")
    ResponseEntity<DocumentService.DocumentView> create(@AuthenticationPrincipal Jwt jwt,
                                                        @PathVariable UUID athleteId,
                                                        @Valid @RequestBody DocumentRequest request) {
        DocumentService.DocumentView created = service.create(Auth.actor(jwt), Auth.roles(jwt), athleteId,
                request.type(), request.fileName(), request.fileUrl(), request.validUntil());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/documents/{documentId}")
    DocumentService.DocumentView update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID documentId,
                                        @Valid @RequestBody DocumentRequest request) {
        return service.update(Auth.actor(jwt), Auth.roles(jwt), documentId, request.type(),
                request.fileName(), request.fileUrl(), request.validUntil());
    }

    @DeleteMapping("/documents/{documentId}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID documentId) {
        service.delete(Auth.actor(jwt), Auth.roles(jwt), documentId);
        return ResponseEntity.noContent().build();
    }
}
