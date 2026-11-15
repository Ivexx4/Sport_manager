package pt.voleibol.gestao.athletes;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import pt.voleibol.gestao.shared.Rules;

@Service
class DocumentService {

    record DocumentView(UUID id, UUID athleteId, String type, String fileName, String fileUrl,
                        LocalDate validUntil, java.time.Instant createdAt, java.time.Instant updatedAt) {
    }

    private final DocumentRepository documents;
    private final AthleteRepository athletes;

    DocumentService(DocumentRepository documents, AthleteRepository athletes) {
        this.documents = documents;
        this.athletes = athletes;
    }

    @Transactional(readOnly = true)
    List<DocumentView> list(UUID actorId, Set<String> roles, UUID athleteId) {
        UUID tenantId = Rules.tenant();
        requireVisibleAthlete(tenantId, athleteId, actorId, roles);
        return documents.list(tenantId, athleteId).stream()
                .map(d -> new DocumentView(d.id(), d.athleteId(), d.type(), d.fileName(), d.fileUrl(),
                        d.validUntil(), d.createdAt(), d.updatedAt()))
                .toList();
    }

    @Transactional
    DocumentView create(UUID actorId, Set<String> roles, UUID athleteId, String type, String fileName,
                       String fileUrl, LocalDate validUntil) {
        UUID tenantId = Rules.tenant();
        requireWritableAthlete(tenantId, athleteId, actorId, roles);
        if (type == null || type.isBlank()) throw Rules.badRequest("Tipo obrigatório");
        UUID id = UUID.randomUUID();
        documents.insert(id, tenantId, athleteId, type.trim(), fileName, fileUrl, validUntil, actorId);
        documents.audit(tenantId, actorId, "ATHLETE_DOCUMENT_CREATED", id);
        return view(documents.findById(tenantId, id).orElseThrow(DocumentService::notFound));
    }

    @Transactional
    DocumentView update(UUID actorId, Set<String> roles, UUID documentId, String type, String fileName,
                       String fileUrl, LocalDate validUntil) {
        UUID tenantId = Rules.tenant();
        DocumentRepository.DocumentRow current = documents.findById(tenantId, documentId)
                .orElseThrow(DocumentService::notFound);
        requireWritableAthlete(tenantId, current.athleteId(), actorId, roles);
        if (type == null || type.isBlank()) throw Rules.badRequest("Tipo obrigatório");
        if (!documents.update(tenantId, documentId, type.trim(), fileName, fileUrl, validUntil)) throw notFound();
        documents.audit(tenantId, actorId, "ATHLETE_DOCUMENT_UPDATED", documentId);
        return view(documents.findById(tenantId, documentId).orElseThrow(DocumentService::notFound));
    }

    @Transactional
    void delete(UUID actorId, Set<String> roles, UUID documentId) {
        UUID tenantId = Rules.tenant();
        DocumentRepository.DocumentRow current = documents.findById(tenantId, documentId)
                .orElseThrow(DocumentService::notFound);
        requireWritableAthlete(tenantId, current.athleteId(), actorId, roles);
        if (!documents.anonymize(tenantId, documentId)) throw notFound();
        documents.audit(tenantId, actorId, "ATHLETE_DOCUMENT_ANONYMIZED", documentId);
    }

    private void requireVisibleAthlete(UUID tenantId, UUID athleteId, UUID actorId, Set<String> roles) {
        if (athletes.findVisible(tenantId, athleteId, actorId, roles).isEmpty()) throw Rules.notFound("Atleta não encontrado");
    }

    private void requireWritableAthlete(UUID tenantId, UUID athleteId, UUID actorId, Set<String> roles) {
        // FINANCEIRO só lê: não deve conseguir escrever mesmo que tenha visibilidade do atleta.
        if (roles.contains("FINANCEIRO") && roles.stream().noneMatch(
                role -> Set.of("ADMIN", "DIRECAO", "TREINADOR", "ATLETA", "ENCARREGADO").contains(role))) {
            throw Rules.forbidden();
        }
        if (athletes.findWritable(tenantId, athleteId, actorId, roles).isEmpty()) throw Rules.notFound("Atleta não encontrado");
    }

    private static ResponseStatusException notFound() {
        return Rules.notFound("Documento não encontrado");
    }

    private static DocumentView view(DocumentRepository.DocumentRow row) {
        return new DocumentView(row.id(), row.athleteId(), row.type(), row.fileName(), row.fileUrl(),
                row.validUntil(), row.createdAt(), row.updatedAt());
    }
}