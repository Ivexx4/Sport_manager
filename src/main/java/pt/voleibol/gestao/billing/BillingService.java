package pt.voleibol.gestao.billing;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import pt.voleibol.gestao.tenancy.TenantContext;

@Service
class BillingService {

    private static final Set<String> WRITERS = Set.of("ADMIN", "DIRECAO", "FINANCEIRO");
    private static final Set<String> PERIODICITIES = Set.of("ONE_TIME", "MONTHLY", "QUARTERLY", "YEARLY");
    private static final Set<String> CHARGE_STATUSES = Set.of("PENDING", "PAID", "OVERDUE", "CANCELLED");
    private static final Set<String> PAYMENT_METHODS = Set.of("CASH", "MULTIBANCO", "MBWAY", "TRANSFER", "OTHER");
    private static final Set<String> LEDGER_TYPES = Set.of("INCOME", "EXPENSE");

    record FeePlanView(UUID id, String name, UUID seasonId, UUID ageGroupId, long amountCents, String periodicity) {
    }
    record ChargeView(UUID id, UUID athleteId, UUID guardianId, UUID feePlanId, long amountCents, LocalDate dueDate, String status) {
    }
    record PaymentView(UUID id, UUID chargeId, String method, long amountCents, String reference, java.time.Instant paidAt) {
    }
    record LedgerView(UUID id, String type, String category, long amountCents, LocalDate entryDate, String description) {
    }

    private final FeePlanRepository feePlans;
    private final ChargeRepository charges;
    private final PaymentRepository payments;
    private final LedgerRepository ledger;

    BillingService(FeePlanRepository feePlans, ChargeRepository charges, PaymentRepository payments, LedgerRepository ledger) {
        this.feePlans = feePlans;
        this.charges = charges;
        this.payments = payments;
        this.ledger = ledger;
    }

    private void requireWriter(Set<String> roles) {
        if (roles.stream().noneMatch(WRITERS::contains)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private UUID tenant() { return TenantContext.current().orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pedido sem clube")); }

    private static void requireAmount(long amountCents) {
        if (amountCents < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valor inválido");
    }

    private static void requireValue(String value, Set<String> allowed, String field) {
        if (value == null || !allowed.contains(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " inválido");
        }
    }

    // --- fee plans ---
    @Transactional(readOnly = true)
    List<FeePlanView> listFeePlans(Set<String> roles) {
        requireWriter(roles);
        return feePlans.list(tenant()).stream().map(f -> new FeePlanView(f.id(), f.name(), f.seasonId(), f.ageGroupId(), f.amountCents(), f.periodicity())).toList();
    }

    @Transactional
    FeePlanView createFeePlan(UUID actorId, Set<String> roles, String name, UUID seasonId, UUID ageGroupId, long amountCents, String periodicity) {
        requireWriter(roles);
        UUID t = tenant();
        if (name == null || name.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome obrigatório");
        requireAmount(amountCents);
        String period = periodicity == null ? "MONTHLY" : periodicity;
        requireValue(period, PERIODICITIES, "Periodicidade");
        if (feePlans.nameExists(t, name.trim(), null)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe um plano com este nome");
        UUID id = UUID.randomUUID();
        feePlans.insert(id, t, name.trim(), seasonId, ageGroupId, amountCents, period, actorId);
        feePlans.audit(t, actorId, "FEE_PLAN_CREATED", id);
        return new FeePlanView(id, name.trim(), seasonId, ageGroupId, amountCents, period);
    }

    @Transactional
    void deleteFeePlan(UUID actorId, Set<String> roles, UUID id) {
        requireWriter(roles);
        UUID t = tenant();
        if (!feePlans.anonymize(t, id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plano não encontrado");
        feePlans.audit(t, actorId, "FEE_PLAN_ANONYMIZED", id);
    }

    // --- charges ---
    @Transactional(readOnly = true)
    List<ChargeView> listCharges(UUID actorId, Set<String> roles, UUID athleteId, String status) {
        boolean writer = roles.contains("ADMIN") || roles.contains("DIRECAO") || roles.contains("FINANCEIRO");
        if (!writer && !roles.contains("ATLETA") && !roles.contains("ENCARREGADO")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return charges.list(tenant(), actorId, roles, athleteId, status).stream()
                .map(c -> new ChargeView(c.id(), c.athleteId(), c.guardianId(), c.feePlanId(), c.amountCents(), c.dueDate(), c.status()))
                .toList();
    }

    @Transactional
    ChargeView createCharge(UUID actorId, Set<String> roles, UUID athleteId, UUID guardianId, UUID feePlanId, long amountCents, LocalDate dueDate, String status) {
        requireWriter(roles);
        UUID t = tenant();
        requireAmount(amountCents);
        if (dueDate == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Data de vencimento obrigatória");
        String chargeStatus = status == null ? "PENDING" : status;
        requireValue(chargeStatus, CHARGE_STATUSES, "Estado");
        if (!charges.athleteExists(t, athleteId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Atleta não encontrado");
        if (guardianId != null && !charges.guardianExists(t, guardianId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Encarregado não encontrado");
        }
        if (feePlanId != null && feePlans.findById(t, feePlanId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plano não encontrado");
        }
        UUID id = UUID.randomUUID();
        charges.insert(id, t, athleteId, guardianId, feePlanId, amountCents, dueDate, chargeStatus, actorId);
        charges.audit(t, actorId, "CHARGE_CREATED", id);
        return new ChargeView(id, athleteId, guardianId, feePlanId, amountCents, dueDate, chargeStatus);
    }

    @Transactional
    void updateChargeStatus(UUID actorId, Set<String> roles, UUID id, String status) {
        requireWriter(roles);
        UUID t = tenant();
        requireValue(status, CHARGE_STATUSES, "Estado");
        if (!charges.updateStatus(t, id, status)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cobrança não encontrada");
        charges.audit(t, actorId, "CHARGE_STATUS_UPDATED", id);
    }

    @Transactional
    void deleteCharge(UUID actorId, Set<String> roles, UUID id) {
        requireWriter(roles);
        UUID t = tenant();
        if (!charges.anonymize(t, id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cobrança não encontrada");
        charges.audit(t, actorId, "CHARGE_ANONYMIZED", id);
    }

    // --- payments ---
    @Transactional(readOnly = true)
    List<PaymentView> listPayments(Set<String> roles, UUID chargeId) {
        requireWriter(roles);
        UUID t = tenant();
        if (charges.findById(t, chargeId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cobrança não encontrada");
        }
        return payments.list(t, chargeId).stream().map(p -> new PaymentView(p.id(), p.chargeId(), p.method(), p.amountCents(), p.reference(), p.paidAt())).toList();
    }

    @Transactional
    PaymentView createPayment(UUID actorId, Set<String> roles, UUID chargeId, String method, long amountCents, String reference) {
        requireWriter(roles);
        UUID t = tenant();
        requireAmount(amountCents);
        String paymentMethod = method == null ? "CASH" : method;
        requireValue(paymentMethod, PAYMENT_METHODS, "Método");
        if (charges.findById(t, chargeId).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cobrança não encontrada");
        UUID id = UUID.randomUUID();
        payments.insert(id, t, chargeId, paymentMethod, amountCents, reference, actorId);
        payments.audit(t, actorId, "PAYMENT_CREATED", id);
        // mark charge paid if fully paid? simple: leave status
        return new PaymentView(id, chargeId, paymentMethod, amountCents, reference, java.time.Instant.now());
    }

    // --- ledger ---
    @Transactional(readOnly = true)
    List<LedgerView> listLedger(Set<String> roles) {
        requireWriter(roles);
        return ledger.list(tenant()).stream().map(l -> new LedgerView(l.id(), l.type(), l.category(), l.amountCents(), l.entryDate(), l.description())).toList();
    }

    @Transactional
    LedgerView createLedger(UUID actorId, Set<String> roles, String type, String category, long amountCents, LocalDate entryDate, String description) {
        requireWriter(roles);
        UUID t = tenant();
        requireAmount(amountCents);
        requireValue(type, LEDGER_TYPES, "Tipo");
        if (category == null || category.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Categoria obrigatória");
        if (entryDate == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Data obrigatória");
        UUID id = UUID.randomUUID();
        ledger.insert(id, t, type, category.trim(), amountCents, entryDate, description, actorId);
        ledger.audit(t, actorId, "LEDGER_ENTRY_CREATED", id);
        return new LedgerView(id, type, category.trim(), amountCents, entryDate, description);
    }

    @Transactional
    void deleteLedger(UUID actorId, Set<String> roles, UUID id) {
        requireWriter(roles);
        UUID t = tenant();
        if (!ledger.anonymize(t, id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Lançamento não encontrado");
        ledger.audit(t, actorId, "LEDGER_ENTRY_ANONYMIZED", id);
    }
}