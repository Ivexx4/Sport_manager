package pt.voleibol.gestao.billing;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pt.voleibol.gestao.shared.AuditLog;
import pt.voleibol.gestao.shared.CurrentUser;
import pt.voleibol.gestao.shared.Role;
import pt.voleibol.gestao.shared.Rules;

@Service
class BillingService {

    record FeePlanView(UUID id, String name, UUID seasonId, UUID ageGroupId, long amountCents, String periodicity) {
    }

    record ChargeView(UUID id, UUID athleteId, UUID guardianId, UUID feePlanId, long amountCents, LocalDate dueDate,
                      String status, String billingPeriod) {
    }

    record GenerateView(String period, int created, int skipped) {
    }

    record PromotionView(UUID id, UUID athleteId, UUID feePlanId, long amountCents, LocalDate validFrom,
                         LocalDate validTo, String reason, boolean active, String applicationScope,
                         String billingPeriod) {
    }

    record PaymentView(UUID id, UUID chargeId, String method, long amountCents, String reference,
                       java.time.Instant paidAt) {
    }

    record LedgerView(UUID id, String type, String category, long amountCents, LocalDate entryDate,
                      String description) {
    }

    private final FeePlanRepository feePlans;
    private final ChargeRepository charges;
    private final PaymentRepository payments;
    private final LedgerRepository ledger;
    private final AuditLog audit;
    private final Clock clock;
    /** Planeamento da geração automática: colaborador interno, com as mesmas dependências. */
    private final ChargePlanner planner;

    BillingService(FeePlanRepository feePlans, ChargeRepository charges, PaymentRepository payments,
                   LedgerRepository ledger, AuditLog audit, Clock clock) {
        this.feePlans = feePlans;
        this.charges = charges;
        this.payments = payments;
        this.ledger = ledger;
        this.audit = audit;
        this.clock = clock;
        this.planner = new ChargePlanner(feePlans, charges, audit, clock);
    }

    private static void requireAmount(long amountCents) {
        if (amountCents < 0) throw Rules.badRequest("Valor inválido");
    }

    // --- fee plans ---
    @Transactional(readOnly = true)
    List<FeePlanView> listFeePlans(CurrentUser user) {
        Rules.requireAny(user, Role.BILLING);
        return feePlans.list(Rules.tenant()).stream().map(f -> new FeePlanView(f.id(), f.name(), f.seasonId(), f.ageGroupId(), f.amountCents(), f.periodicity())).toList();
    }

    @Transactional
    FeePlanView createFeePlan(CurrentUser user, CreateFeePlanCommand command) {
        Rules.requireAny(user, Role.BILLING);
        UUID t = Rules.tenant();
        String normalizedName = Rules.requiredText(command.name());
        requireAmount(command.amountCents());
        String period = Rules.parse(FeePeriodicity.class, command.periodicity(), "Periodicidade",
                FeePeriodicity.DEFAULT).name();
        if (feePlans.nameExists(t, normalizedName, null)) throw Rules.conflict("Já existe um plano com este nome");
        UUID id = UUID.randomUUID();
        feePlans.insert(id, t, normalizedName, command.seasonId(), command.ageGroupId(), command.amountCents(),
                period, user.id());
        audit.record(t, user.id(), "FEE_PLAN_CREATED", "fee_plan", id);
        return new FeePlanView(id, normalizedName, command.seasonId(), command.ageGroupId(),
                command.amountCents(), period);
    }

    @Transactional
    void deleteFeePlan(CurrentUser user, UUID id) {
        Rules.requireAny(user, Role.BILLING);
        UUID t = Rules.tenant();
        if (!feePlans.anonymize(t, id)) throw Rules.notFound("Plano não encontrado");
        audit.record(t, user.id(), "FEE_PLAN_ANONYMIZED", "fee_plan", id);
    }

    @Transactional(readOnly = true)
    List<PromotionView> listPromotions(CurrentUser user, UUID athleteId) {
        Rules.requireAny(user, Role.BILLING);
        return feePlans.listPromotions(Rules.tenant(), athleteId).stream()
                .map(p -> new PromotionView(p.id(), p.athleteId(), p.feePlanId(), p.amountCents(),
                        p.validFrom(), p.validTo(), p.reason(), p.active(), p.applicationScope(), p.billingPeriod())).toList();
    }

    @Transactional
    PromotionView createPromotion(CurrentUser user, CreatePromotionCommand command) {
        Rules.requireAny(user, Role.BILLING);
        UUID t = Rules.tenant();
        requireAmount(command.amountCents());
        LocalDate validFrom = command.validFrom();
        LocalDate validTo = command.validTo();
        if (validFrom == null || (validTo != null && validTo.isBefore(validFrom)))
            throw Rules.badRequest("Período da promoção inválido");
        String scope = Rules.parse(PromotionScope.class, command.applicationScope(), "Âmbito da promoção",
                PromotionScope.DEFAULT).name();
        boolean single = PromotionScope.SINGLE.name().equals(scope);
        String billingPeriod = single ? command.billingPeriod() : null;
        if (single && (billingPeriod == null || billingPeriod.isBlank()))
            throw Rules.badRequest("Período obrigatório para promoção de pagamento único");
        if (single) {
            try {
                YearMonth.parse(billingPeriod);
            } catch (DateTimeParseException ex) {
                throw Rules.badRequest("Período de pagamento inválido");
            }
        }
        if (!charges.athleteExists(t, command.athleteId())) throw Rules.notFound("Atleta não encontrado");
        if (command.feePlanId() != null && feePlans.findById(t, command.feePlanId()).isEmpty())
            throw Rules.notFound("Plano não encontrado");
        UUID id = UUID.randomUUID();
        feePlans.insertPromotion(id, t, command.athleteId(), command.feePlanId(), command.amountCents(),
                validFrom, validTo, command.reason(), scope, billingPeriod, user.id());
        return new PromotionView(id, command.athleteId(), command.feePlanId(), command.amountCents(), validFrom,
                validTo, command.reason(), true, scope, billingPeriod);
    }

    @Transactional
    void setPromotionActive(CurrentUser user, UUID id, boolean active) {
        Rules.requireAny(user, Role.BILLING);
        if (!feePlans.setPromotionActive(Rules.tenant(), id, active)) throw Rules.notFound("Promoção não encontrada");
    }

    // --- charges ---
    @Transactional(readOnly = true)
    List<ChargeView> listCharges(CurrentUser user, UUID athleteId, String status) {
        boolean writer = user.canManageBilling();
        if (!writer && !user.has(Role.ATLETA) && !user.has(Role.ENCARREGADO)) throw Rules.forbidden();
        return charges.list(Rules.tenant(), user, athleteId, status).stream()
                .map(c -> new ChargeView(c.id(), c.athleteId(), c.guardianId(), c.feePlanId(), c.amountCents(), c.dueDate(), c.status(), c.billingPeriod()))
                .toList();
    }

    @Transactional
    ChargeView createCharge(CurrentUser user, CreateChargeCommand command) {
        Rules.requireAny(user, Role.BILLING);
        UUID t = Rules.tenant();
        requireAmount(command.amountCents());
        if (command.dueDate() == null) throw Rules.badRequest("Data de vencimento obrigatória");
        String chargeStatus = Rules.parse(ChargeStatus.class, command.status(), "Estado", ChargeStatus.DEFAULT).name();
        if (!charges.athleteExists(t, command.athleteId())) throw Rules.notFound("Atleta não encontrado");
        if (command.guardianId() != null && !charges.guardianExists(t, command.guardianId()))
            throw Rules.notFound("Encarregado não encontrado");
        String billingPeriod = null;
        if (command.feePlanId() != null) {
            FeePlanRepository.Row plan = feePlans.findById(t, command.feePlanId())
                    .orElseThrow(() -> Rules.notFound("Plano não encontrado"));
            if (FeePeriodicity.ONE_TIME.name().equals(plan.periodicity()))
                billingPeriod = FeePeriodicity.ONE_TIME.name();
        }
        UUID id = UUID.randomUUID();
        try {
            charges.insert(id, t, command.athleteId(), command.guardianId(), command.feePlanId(),
                    command.amountCents(), command.dueDate(), chargeStatus, billingPeriod, user.id());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            if (FeePeriodicity.ONE_TIME.name().equals(billingPeriod)) throw Rules.conflict("Esta taxa já foi registada para o atleta");
            throw e;
        }
        audit.record(t, user.id(), "CHARGE_CREATED", "charge", id);
        return new ChargeView(id, command.athleteId(), command.guardianId(), command.feePlanId(),
                command.amountCents(), command.dueDate(), chargeStatus, billingPeriod);
    }

    // --- geração automática a partir dos planos ---

    @Transactional
    GenerateView generateCharges(CurrentUser user, String period, UUID feePlanId, boolean dryRun) {
        Rules.requireAny(user, Role.BILLING);
        return planner.generate(Rules.tenant(), user.id(), parsePeriod(period), feePlanId, dryRun);
    }

    /**
     * Entrada usada pelo agendador, depois de o tenant estar no {@link TenantContext}.
     * Não valida papéis: a tarefa corre em nome do clube, sem utilizador.
     */
    @Transactional
    GenerateView generateChargesForTenant(UUID tenantId, YearMonth period, UUID feePlanId) {
        return planner.generate(tenantId, null, period, feePlanId, false);
    }

    private YearMonth parsePeriod(String period) {
        if (period == null || period.isBlank()) return YearMonth.now(clock);
        try {
            return YearMonth.parse(period.trim());
        } catch (DateTimeParseException e) {
            throw Rules.badRequest("Período inválido (use AAAA-MM)");
        }
    }

    @Transactional
    void updateChargeStatus(CurrentUser user, UUID id, String status) {
        Rules.requireAny(user, Role.BILLING);
        UUID t = Rules.tenant();
        String normalizedStatus = Rules.parse(ChargeStatus.class, status, "Estado").name();
        if (!charges.updateStatus(t, id, normalizedStatus)) throw Rules.notFound("Cobrança não encontrada");
        audit.record(t, user.id(), "CHARGE_STATUS_UPDATED", "charge", id);
    }

    @Transactional
    void deleteCharge(CurrentUser user, UUID id) {
        Rules.requireAny(user, Role.BILLING);
        UUID t = Rules.tenant();
        if (!charges.anonymize(t, id)) throw Rules.notFound("Cobrança não encontrada");
        audit.record(t, user.id(), "CHARGE_ANONYMIZED", "charge", id);
    }

    // --- payments ---
    @Transactional(readOnly = true)
    List<PaymentView> listPayments(CurrentUser user, UUID chargeId) {
        Rules.requireAny(user, Role.BILLING);
        UUID t = Rules.tenant();
        if (charges.findById(t, chargeId).isEmpty()) throw Rules.notFound("Cobrança não encontrada");
        return payments.list(t, chargeId).stream().map(p -> new PaymentView(p.id(), p.chargeId(), p.method(), p.amountCents(), p.reference(), p.paidAt())).toList();
    }

    @Transactional
    PaymentView createPayment(CurrentUser user, UUID chargeId, String method, long amountCents, String reference) {
        Rules.requireAny(user, Role.BILLING);
        UUID t = Rules.tenant();
        requireAmount(amountCents);
        String paymentMethod = Rules.parse(PaymentMethod.class, method, "Método", PaymentMethod.DEFAULT).name();
        ChargeRepository.Row charge = charges.findById(t, chargeId)
                .orElseThrow(() -> Rules.notFound("Cobrança não encontrada"));
        UUID id = UUID.randomUUID();
        payments.insert(id, t, chargeId, paymentMethod, amountCents, reference, user.id());
        audit.record(t, user.id(), "PAYMENT_CREATED", "payment", id);
        // Recebido o valor em dívida, a cobrança passa a PAID na mesma transação (até lá, fica como está).
        if (!ChargeStatus.PAID.name().equals(charge.status())
                && payments.sumAmount(t, chargeId) >= charge.amountCents()
                && charges.updateStatus(t, chargeId, ChargeStatus.PAID.name())) {
            audit.record(t, user.id(), "CHARGE_STATUS_UPDATED", "charge", chargeId);
        }
        return new PaymentView(id, chargeId, paymentMethod, amountCents, reference, java.time.Instant.now());
    }

    // --- ledger ---
    @Transactional(readOnly = true)
    List<LedgerView> listLedger(CurrentUser user) {
        Rules.requireAny(user, Role.BILLING);
        return ledger.list(Rules.tenant()).stream().map(l -> new LedgerView(l.id(), l.type(), l.category(), l.amountCents(), l.entryDate(), l.description())).toList();
    }

    @Transactional
    LedgerView createLedger(CurrentUser user, String type, String category, long amountCents, LocalDate entryDate, String description) {
        Rules.requireAny(user, Role.BILLING);
        UUID t = Rules.tenant();
        requireAmount(amountCents);
        String normalizedType = Rules.parse(LedgerType.class, type, "Tipo").name();
        if (category == null || category.isBlank()) throw Rules.badRequest("Categoria obrigatória");
        if (entryDate == null) throw Rules.badRequest("Data obrigatória");
        UUID id = UUID.randomUUID();
        ledger.insert(id, t, normalizedType, category.trim(), amountCents, entryDate, description, user.id());
        audit.record(t, user.id(), "LEDGER_ENTRY_CREATED", "ledger_entry", id);
        return new LedgerView(id, normalizedType, category.trim(), amountCents, entryDate, description);
    }

    @Transactional
    void deleteLedger(CurrentUser user, UUID id) {
        Rules.requireAny(user, Role.BILLING);
        UUID t = Rules.tenant();
        if (!ledger.anonymize(t, id)) throw Rules.notFound("Lançamento não encontrado");
        audit.record(t, user.id(), "LEDGER_ENTRY_ANONYMIZED", "ledger_entry", id);
    }
}