package pt.voleibol.gestao.athletes;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import pt.voleibol.gestao.shared.AuditLog;
import pt.voleibol.gestao.shared.CurrentUser;
import pt.voleibol.gestao.shared.Role;
import pt.voleibol.gestao.shared.Rules;

@Service
class DocumentService {

    record DocumentView(UUID id, UUID athleteId, String type, String fileName, String fileUrl,
                        LocalDate validUntil, java.time.Instant createdAt, java.time.Instant updatedAt) {
    }

    private final DocumentRepository documents;
    private final AthleteRepository athletes;
    private final AuditLog audit;

    DocumentService(DocumentRepository documents, AthleteRepository athletes, AuditLog audit) {
        this.documents = documents;
        this.athletes = athletes;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    List<DocumentView> list(CurrentUser user, UUID athleteId) {
        UUID tenantId = Rules.tenant();
        requireVisibleAthlete(tenantId, athleteId, user);
        return documents.list(tenantId, athleteId).stream()
                .map(d -> new DocumentView(d.id(), d.athleteId(), d.type(), d.fileName(), d.fileUrl(),
                        d.validUntil(), d.createdAt(), d.updatedAt()))
                .toList();
    }

    @Transactional
    DocumentView create(CurrentUser user, UUID athleteId, String type, String fileName,
                       String fileUrl, LocalDate validUntil) {
        UUID tenantId = Rules.tenant();
        requireWritableAthlete(tenantId, athleteId, user);
        if (type == null || type.isBlank()) throw Rules.badRequest("Tipo obrigatório");
        UUID id = UUID.randomUUID();
        documents.insert(id, tenantId, athleteId, type.trim(), fileName, fileUrl, validUntil, user.id());
        audit.record(tenantId, user.id(), "ATHLETE_DOCUMENT_CREATED", "document", id);
        return view(documents.findById(tenantId, id).orElseThrow(DocumentService::notFound));
    }

    @Transactional
    DocumentView update(CurrentUser user, UUID documentId, String type, String fileName,
                       String fileUrl, LocalDate validUntil) {
        UUID tenantId = Rules.tenant();
        DocumentRepository.DocumentRow current = documents.findById(tenantId, documentId)
                .orElseThrow(DocumentService::notFound);
        requireWritableAthlete(tenantId, current.athleteId(), user);
        if (type == null || type.isBlank()) throw Rules.badRequest("Tipo obrigatório");
        if (!documents.update(tenantId, documentId, type.trim(), fileName, fileUrl, validUntil)) throw notFound();
        audit.record(tenantId, user.id(), "ATHLETE_DOCUMENT_UPDATED", "document", documentId);
        return view(documents.findById(tenantId, documentId).orElseThrow(DocumentService::notFound));
    }

    @Transactional
    void delete(CurrentUser user, UUID documentId) {
        UUID tenantId = Rules.tenant();
        DocumentRepository.DocumentRow current = documents.findById(tenantId, documentId)
                .orElseThrow(DocumentService::notFound);
        requireWritableAthlete(tenantId, current.athleteId(), user);
        if (!documents.anonymize(tenantId, documentId)) throw notFound();
        audit.record(tenantId, user.id(), "ATHLETE_DOCUMENT_ANONYMIZED", "document", documentId);
    }

    private void requireVisibleAthlete(UUID tenantId, UUID athleteId, CurrentUser user) {
        if (athletes.findVisible(tenantId, athleteId, user).isEmpty()) throw Rules.notFound("Atleta não encontrado");
    }

    private void requireWritableAthlete(UUID tenantId, UUID athleteId, CurrentUser user) {
        // FINANCEIRO só lê: não deve conseguir escrever mesmo que tenha visibilidade do atleta.
        if (user.has(Role.FINANCEIRO) && user.roles().size() == 1) throw Rules.forbidden();
        if (athletes.findWritable(tenantId, athleteId, user).isEmpty()) throw Rules.notFound("Atleta não encontrado");
    }

    private static ResponseStatusException notFound() {
        return Rules.notFound("Documento não encontrado");
    }

    private static DocumentView view(DocumentRepository.DocumentRow row) {
        return new DocumentView(row.id(), row.athleteId(), row.type(), row.fileName(), row.fileUrl(),
                row.validUntil(), row.createdAt(), row.updatedAt());
    }
}