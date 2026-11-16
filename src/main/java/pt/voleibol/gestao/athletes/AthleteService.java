package pt.voleibol.gestao.athletes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import pt.voleibol.gestao.shared.CurrentUser;
import pt.voleibol.gestao.shared.Rules;
import pt.voleibol.gestao.billing.ChargeRepository;
import pt.voleibol.gestao.teams.SeasonEligibility;

/**
 * Atletas e encarregados de educação, com autorização por recurso:
 * <ul>
 *   <li>ADMIN e DIRECAO gerem todos os atletas do clube;</li>
 *   <li>TREINADOR lê e altera os atletas das equipas em que é staff;</li>
 *   <li>ATLETA lê e altera apenas o seu registo;</li>
 *   <li>ENCARREGADO lê e altera apenas os seus educandos;</li>
 *   <li>FINANCEIRO só lê.</li>
 * </ul>
 * Criar, remover (desativar e anonimizar), ligar contas, mudar estado e gerir encarregados é exclusivo de
 * ADMIN/DIRECAO.
 */
@Service
class AthleteService {

    record AthleteView(UUID id, String fullName, LocalDate birthDate, String gender, String nif,
                       String email, String phone, String licenseNumber, String status, UUID userId,
                       Instant createdAt, Instant updatedAt) {
    }

    record Page(List<AthleteView> items, int page, int size, long total) {
    }

    record GuardianView(UUID userId, String name, String email, String relationship) {
    }

    private final AthleteRepository athletes;
    private final ChargeRepository charges;
    private final SeasonEligibility eligibility;

    AthleteService(AthleteRepository athletes, ChargeRepository charges, SeasonEligibility eligibility) {
        this.athletes = athletes;
        this.charges = charges;
        this.eligibility = eligibility;
    }

    @Transactional(readOnly = true)
    Page list(CurrentUser user, String query, String status, UUID teamId, int page, int size) {
        if (page < 0) throw Rules.badRequest("Página inválida");
        if (size < 1 || size > 100) throw Rules.badRequest("Tamanho da página entre 1 e 100");
        UUID tenantId = Rules.tenant();
        AthleteStatus normalizedStatus = status == null || status.isBlank() ? null
                : Rules.parse(AthleteStatus.class, status, "Estado");
        List<AthleteView> items = athletes
                .list(tenantId, user, query, normalizedStatus, teamId, size, (long) page * size)
                .stream().map(AthleteService::view).toList();
        long total = athletes.count(tenantId, user, query, normalizedStatus, teamId);
        return new Page(items, page, size, total);
    }

    @Transactional(readOnly = true)
    AthleteView get(CurrentUser user, UUID athleteId) {
        UUID tenantId = Rules.tenant();
        return athletes.findVisible(tenantId, athleteId, user)
                .map(AthleteService::view)
                .orElseThrow(AthleteService::notFound);
    }

    @Transactional
    AthleteView create(CurrentUser user, AthleteRepository.Data data) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(user);

        if (data.licenseNumber() != null && athletes.licenseExists(tenantId, data.licenseNumber(), null))
            throw Rules.conflict("Já existe um atleta com esta licença");
        if (data.userId() != null) {
            requireMembership(tenantId, data.userId());
            if (athletes.userLinked(tenantId, data.userId(), null)) throw Rules.conflict("Esta conta já está ligada a um atleta");
        }

        UUID id = UUID.randomUUID();
        try {
            athletes.insert(id, tenantId, data, user.id());
        } catch (DuplicateKeyException e) {
            throw Rules.conflict("Já existe um atleta com estes dados");
        }
        athletes.audit(tenantId, user.id(), "ATHLETE_CREATED", id);
        return get(user, id);
    }

    @Transactional
    int importCsv(CurrentUser user, Reader reader) throws IOException {
        Rules.requireManager(user);
        UUID tenantId = Rules.tenant();
        BufferedReader br = reader instanceof BufferedReader ? (BufferedReader) reader
                : new BufferedReader(reader);
        String line;
        int count = 0;
        boolean header = true;
        while ((line = br.readLine()) != null) {
            if (line.isBlank()) continue;
            String[] cols = line.split(",", -1);
            if (header) {
                header = false;
                // skip header row if first column looks like a header
                if (cols.length > 0 && cols[0].trim().equalsIgnoreCase("fullName")) continue;
            }
            // expected: fullName,birthDate,gender,nif,email,phone,licenseNumber,status
            if (cols.length < 3) continue;
            String fullName = cols[0].trim();
            if (fullName.isBlank()) continue;
            LocalDate birthDate;
            try {
                birthDate = LocalDate.parse(cols[1].trim());
            } catch (Exception e) {
                continue;
            }
            String gender = cols[2].trim().toUpperCase(Locale.ROOT);
            if (!gender.equals("F") && !gender.equals("M")) gender = "M";
            String nif = cols.length > 3 ? trimToNull(cols[3]) : null;
            String email = cols.length > 4 ? trimToNull(cols[4]) : null;
            String phone = cols.length > 5 ? trimToNull(cols[5]) : null;
            String license = cols.length > 6 ? trimToNull(cols[6]) : null;
            String status = Rules.find(AthleteStatus.class, cols.length > 7 ? cols[7] : null)
                    .orElse(AthleteStatus.DEFAULT).name();

            AthleteRepository.Data data = new AthleteRepository.Data(fullName, birthDate, gender, nif, email,
                    phone, license, status, null);
            if (license != null && athletes.licenseExists(tenantId, license, null)) continue;
            UUID id = UUID.randomUUID();
            try {
                athletes.insert(id, tenantId, data, user.id());
            } catch (DuplicateKeyException e) {
                continue;
            }
            athletes.audit(tenantId, user.id(), "ATHLETE_CREATED", id);
            count++;
        }
        return count;
    }

    @Transactional
    AthleteView update(CurrentUser user, UUID athleteId, AthleteRepository.Data data) {
        UUID tenantId = Rules.tenant();
        AthleteRepository.AthleteRow current = requireWritable(tenantId, athleteId, user);

        if (data.licenseNumber() != null
                && athletes.licenseExists(tenantId, data.licenseNumber(), athleteId)) {
            throw Rules.conflict("Já existe um atleta com esta licença");
        }
        try {
            if (!athletes.update(tenantId, athleteId, data)) throw notFound();
        } catch (DuplicateKeyException e) {
            throw Rules.conflict("Já existe um atleta com esta licença");
        }
        // A data de nascimento decide o escalão: uma alteração que torne o atleta inelegível desfaz
        // a edição, em vez de deixar inscrições ou plantéis ilegais.
        if (!Objects.equals(current.birthDate(), data.birthDate())) {
            eligibility.requireAthleteStillEligible(tenantId, athleteId);
        }
        athletes.audit(tenantId, user.id(), "ATHLETE_UPDATED", athleteId);
        return get(user, athleteId);
    }

    @Transactional
    void delete(CurrentUser user, UUID athleteId) {
        UUID tenantId = Rules.tenant();
        requireVisible(tenantId, athleteId, user);
        // 403 se o vê mas não o pode apagar.
        if (!user.isManager()) throw Rules.forbidden();
        // Remoção não destrutiva: desativa e anonimiza, preservando a linha e as relações.
        if (!athletes.anonymize(tenantId, athleteId)) throw notFound();
        athletes.deleteGuardian(tenantId, athleteId, null);
        athletes.audit(tenantId, user.id(), "ATHLETE_ANONYMIZED", athleteId);
    }

    // --- conta e estado ---

    @Transactional
    AthleteView linkAccount(CurrentUser user, UUID athleteId, UUID userId) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(user);
        requireVisible(tenantId, athleteId, user);
        requireMembership(tenantId, userId);
        if (athletes.userLinked(tenantId, userId, athleteId)) throw Rules.conflict("Esta conta já está ligada a outro atleta");
        try {
            if (!athletes.linkUser(tenantId, athleteId, userId)) throw notFound();
        } catch (DuplicateKeyException e) {
            throw Rules.conflict("Esta conta já está ligada a outro atleta");
        }
        athletes.audit(tenantId, user.id(), "ATHLETE_ACCOUNT_LINKED", athleteId);
        return get(user, athleteId);
    }

    @Transactional
    AthleteView unlinkAccount(CurrentUser user, UUID athleteId) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(user);
        requireVisible(tenantId, athleteId, user);
        if (!athletes.unlinkUser(tenantId, athleteId)) throw notFound();
        athletes.audit(tenantId, user.id(), "ATHLETE_ACCOUNT_UNLINKED", athleteId);
        return get(user, athleteId);
    }

    @Transactional
    AthleteView changeStatus(CurrentUser user, UUID athleteId, String status) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(user);
        requireVisible(tenantId, athleteId, user);
        if (!athletes.updateStatus(tenantId, athleteId, Rules.parse(AthleteStatus.class, status, "Estado").name()))
            throw notFound();

        athletes.audit(tenantId, user.id(), "ATHLETE_STATUS_CHANGED", athleteId);
        return get(user, athleteId);
    }

    @Transactional
    AthleteView registerWithdrawal(CurrentUser user, UUID athleteId, LocalDate withdrawalDate) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(user);
        if (withdrawalDate == null) throw Rules.badRequest("Data de desistência obrigatória");
        requireVisible(tenantId, athleteId, user);
        if (!athletes.registerWithdrawal(tenantId, athleteId, withdrawalDate)) throw notFound();
        charges.cancelFutureRecurring(tenantId, athleteId, withdrawalDate);
        athletes.audit(tenantId, user.id(), "ATHLETE_WITHDRAWN", athleteId);
        return get(user, athleteId);
    }

    // --- encarregados ---

    @Transactional(readOnly = true)
    List<GuardianView> listGuardians(CurrentUser user, UUID athleteId) {
        UUID tenantId = Rules.tenant();
        requireVisible(tenantId, athleteId, user);
        return athletes.listGuardians(tenantId, athleteId).stream()
                .map(g -> new GuardianView(g.userId(), g.name(), g.email(), g.relationship()))
                .toList();
    }

    @Transactional
    List<GuardianView> addGuardian(CurrentUser user, UUID athleteId, UUID userId,
                                   String relationship) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(user);
        requireVisible(tenantId, athleteId, user);
        requireMembership(tenantId, userId);
        if (athletes.guardianExists(tenantId, athleteId, userId)) throw Rules.conflict("Este encarregado já está associado ao atleta");
        try {
            athletes.insertGuardian(tenantId, athleteId, userId, trimToNull(relationship), user.id());
        } catch (DuplicateKeyException e) {
            throw Rules.conflict("Este encarregado já está associado ao atleta");
        }
        athletes.audit(tenantId, user.id(), "ATHLETE_GUARDIAN_ADDED", athleteId);
        return listGuardians(user, athleteId);
    }

    @Transactional
    void removeGuardian(CurrentUser user, UUID athleteId, UUID userId) {
        UUID tenantId = Rules.tenant();
        Rules.requireManager(user);
        requireVisible(tenantId, athleteId, user);
        if (!athletes.deleteGuardian(tenantId, athleteId, userId)) throw Rules.notFound("Encarregado não associado ao atleta");
        athletes.audit(tenantId, user.id(), "ATHLETE_GUARDIAN_REMOVED", athleteId);
    }

    // --- auxiliares ---

    static AthleteRepository.Data data(String fullName, LocalDate birthDate, String gender, String nif,
                                       String email, String phone, String licenseNumber, String status,
                                       UUID userId) {
        return new AthleteRepository.Data(fullName.trim(), birthDate,
                gender.trim().toUpperCase(Locale.ROOT), trimToNull(nif),
                trimToNull(email) == null ? null : email.trim().toLowerCase(Locale.ROOT),
                trimToNull(phone), trimToNull(licenseNumber),
                Rules.parse(AthleteStatus.class, status, "Estado", AthleteStatus.DEFAULT).name(), userId);
    }

    private void requireVisible(UUID tenantId, UUID athleteId, CurrentUser user) {
        athletes.findVisible(tenantId, athleteId, user).orElseThrow(AthleteService::notFound);
    }

    private AthleteRepository.AthleteRow requireWritable(UUID tenantId, UUID athleteId, CurrentUser user) {
        Optional<AthleteRepository.AthleteRow> writable =
                athletes.findWritable(tenantId, athleteId, user);
        if (writable.isPresent()) return writable.get();
        if (athletes.findVisible(tenantId, athleteId, user).isPresent()) throw Rules.forbidden();
        throw notFound();
    }

    private void requireMembership(UUID tenantId, UUID userId) {
        if (!athletes.activeMembershipExists(tenantId, userId)) throw Rules.notFound("Utilizador sem papel ativo no clube");
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ResponseStatusException notFound() {
        return Rules.notFound("Atleta não encontrado");
    }

    private static AthleteView view(AthleteRepository.AthleteRow row) {
        return new AthleteView(row.id(), row.fullName(), row.birthDate(), row.gender(), row.nif(), row.email(),
                row.phone(), row.licenseNumber(), row.status(), row.userId(), row.createdAt(), row.updatedAt());
    }
}
