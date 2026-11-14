package pt.voleibol.gestao.athletes;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
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

@RestController
@RequestMapping("/api/v1")
class DocumentController {

    record DocumentRequest(@NotBlank String type, String fileName, String fileUrl, LocalDate validUntil) {
    }

    record DocumentResponse(UUID id, UUID athleteId, String type, String fileName, String fileUrl,
                            LocalDate validUntil, java.time.Instant createdAt, java.time.Instant updatedAt) {
        static DocumentResponse from(DocumentService.DocumentView v) {
            return new DocumentResponse(v.id(), v.athleteId(), v.type(), v.fileName(), v.fileUrl(),
                    v.validUntil(), v.createdAt(), v.updatedAt());
        }
    }

    private final DocumentService service;

    DocumentController(DocumentService service) {
        this.service = service;
    }

    @GetMapping("/athletes/{athleteId}/documents")
    List<DocumentResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID athleteId) {
        return service.list(actor(jwt), roles(jwt), athleteId).stream().map(DocumentResponse::from).toList();
    }

    @PostMapping("/athletes/{athleteId}/documents")
    ResponseEntity<DocumentResponse> create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID athleteId,
                                           @Valid @RequestBody DocumentRequest request) {
        DocumentResponse created = DocumentResponse.from(service.create(
                actor(jwt), roles(jwt), athleteId, request.type(), request.fileName(), request.fileUrl(),
                request.validUntil()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/documents/{documentId}")
    DocumentResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID documentId,
                            @Valid @RequestBody DocumentRequest request) {
        return DocumentResponse.from(service.update(actor(jwt), roles(jwt), documentId, request.type(),
                request.fileName(), request.fileUrl(), request.validUntil()));
    }

    @DeleteMapping("/documents/{documentId}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID documentId) {
        service.delete(actor(jwt), roles(jwt), documentId);
        return ResponseEntity.noContent().build();
    }

    private static UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static Set<String> roles(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles == null ? Set.of() : Set.copyOf(roles);
    }
}