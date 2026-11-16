package pt.voleibol.gestao.billing;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    BillingService(FeePlanRepository feePlans, ChargeRepository charges, PaymentRepository payments, LedgerRepository ledger) {
        this.feePlans = feePlans;
        this.charges = charges;
        this.payments = payments;
        this.ledger = ledger;
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
    FeePlanView createFeePlan(CurrentUser user, String name, UUID seasonId, UUID ageGroupId, long amountCents, String periodicity) {
        Rules.requireAny(user, Role.BILLING);
        UUID t = Rules.tenant();
        String normalizedName = Rules.requiredText(name);
        requireAmount(amountCents);
        String period = Rules.parse(FeePeriodicity.class, periodicity, "Periodicidade", FeePeriodicity.DEFAULT).name();
        if (feePlans.nameExists(t, normalizedName, null)) throw Rules.conflict("Já existe um plano com este nome");
        UUID id = UUID.randomUUID();
        feePlans.insert(id, t, normalizedName, seasonId, ageGroupId, amountCents, period, user.id());
        feePlans.audit(t, user.id(), "FEE_PLAN_CREATED", id);
        return new FeePlanView(id, normalizedName, seasonId, ageGroupId, amountCents, period);
    }

    @Transactional
    void deleteFeePlan(CurrentUser user, UUID id) {
        Rules.requireAny(user, Role.BILLING);
        UUID t = Rules.tenant();
        if (!feePlans.anonymize(t, id)) throw Rules.notFound("Plano não encontrado");
        feePlans.audit(t, user.id(), "FEE_PLAN_ANONYMIZED", id);
    }

    @Transactional(readOnly = true)
    List<PromotionView> listPromotions(CurrentUser user, UUID athleteId) {
        Rules.requireAny(user, Role.BILLING);
        return feePlans.listPromotions(Rules.tenant(), athleteId).stream()
                .map(p -> new PromotionView(p.id(), p.athleteId(), p.feePlanId(), p.amountCents(),
                        p.validFrom(), p.validTo(), p.reason(), p.active(), p.applicationScope(), p.billingPeriod())).toList();
    }

    @Transactional
    PromotionView createPromotion(CurrentUser user, UUID athleteId, UUID feePlanId,
                                  long amountCents, LocalDate validFrom, LocalDate validTo, String reason,
                                  String applicationScope, String billingPeriod) {
        Rules.requireAny(user, Role.BILLING);
        UUID t = Rules.tenant();
        requireAmount(amountCents);
        if (validFrom == null || (validTo != null && validTo.isBefore(validFrom))) throw Rules.badRequest("Período da promoção inválido");
        String scope = Rules.parse(PromotionScope.class, applicationScope, "Âmbito da promoção",
                PromotionScope.DEFAULT).name();
        if (PromotionScope.SINGLE.name().equals(scope) && (billingPeriod == null || billingPeriod.isBlank()))
            throw Rules.badRequest("Período obrigatório para promoção de pagamento único");
        if (PromotionScope.SINGLE.name().equals(scope)) {
            try {
                YearMonth.parse(billingPeriod);
            } catch (DateTimeParseException ex) {
                throw Rules.badRequest("Período de pagamento inválido");
            }
        }
        if (!charges.athleteExists(t, athleteId)) throw Rules.notFound("Atleta não encontrado");
        if (feePlanId != null && feePlans.findById(t, feePlanId).isEmpty()) throw Rules.notFound("Plano não encontrado");
        UUID id = UUID.randomUUID();
        feePlans.insertPromotion(id, t, athleteId, feePlanId, amountCents, validFrom, validTo, reason,
                scope, PromotionScope.SINGLE.name().equals(scope) ? billingPeriod : null, user.id());
        return new PromotionView(id, athleteId, feePlanId, amountCents, validFrom, validTo, reason, true,
                scope, PromotionScope.SINGLE.name().equals(scope) ? billingPeriod : null);
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
    ChargeView createCharge(CurrentUser user, UUID athleteId, UUID guardianId, UUID feePlanId, long amountCents, LocalDate dueDate, String status) {
        Rules.requireAny(user, Role.BILLING);
        UUID t = Rules.tenant();
        requireAmount(amountCents);
        if (dueDate == null) throw Rules.badRequest("Data de vencimento obrigatória");
        String chargeStatus = Rules.parse(ChargeStatus.class, status, "Estado", ChargeStatus.DEFAULT).name();
        if (!charges.athleteExists(t, athleteId)) throw Rules.notFound("Atleta não encontrado");
        if (guardianId != null && !charges.guardianExists(t, guardianId)) throw Rules.notFound("Encarregado não encontrado");
        String billingPeriod = null;
        if (feePlanId != null) {
            FeePlanRepository.Row plan = feePlans.findById(t, feePlanId)
                    .orElseThrow(() -> Rules.notFound("Plano não encontrado"));
            if (FeePeriodicity.ONE_TIME.name().equals(plan.periodicity()))
                billingPeriod = FeePeriodicity.ONE_TIME.name();
        }
        UUID id = UUID.randomUUID();
        try {
            charges.insert(id, t, athleteId, guardianId, feePlanId, amountCents, dueDate, chargeStatus, billingPeriod, user.id());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            if (FeePeriodicity.ONE_TIME.name().equals(billingPeriod)) throw Rules.conflict("Esta taxa já foi registada para o atleta");
            throw e;
        }
        charges.audit(t, user.id(), "CHARGE_CREATED", id);
        return new ChargeView(id, athleteId, guardianId, feePlanId, amountCents, dueDate, chargeStatus, billingPeriod);
    }

    // --- geração automática a partir dos planos ---

    @Transactional
    GenerateView generateCharges(CurrentUser user, String period, UUID feePlanId, boolean dryRun) {
        Rules.requireAny(user, Role.BILLING);
        return generate(Rules.tenant(), user.id(), parsePeriod(period), feePlanId, dryRun);
    }

    /**
     * Entrada usada pelo agendador, depois de o tenant estar no {@link TenantContext}.
     * Não valida papéis: a tarefa corre em nome do clube, sem utilizador.
     */
    @Transactional
    GenerateView generateChargesForTenant(UUID tenantId, YearMonth period, UUID feePlanId) {
        return generate(tenantId, null, period, feePlanId, false);
    }

    private GenerateView generate(UUID tenantId, UUID actorId, YearMonth period, UUID feePlanId, boolean dryRun) {
        List<FeePlanRepository.Row> plans;
        if (feePlanId == null) plans = feePlans.list(tenantId); else {
            plans = List.of(feePlans.findById(tenantId, feePlanId)
                    .orElseThrow(() -> Rules.notFound("Plano não encontrado")));
        }
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Lisbon"));
        int created = 0;
        int skipped = 0;
        for (FeePlanRepository.Row plan : plans) {
            LocalDate periodDate = period.atDay(1);
            if (!plan.active()) continue;
            FeePeriodicity periodicity = Rules.find(FeePeriodicity.class, plan.periodicity())
                    .orElse(FeePeriodicity.DEFAULT);
            if (!periodicity.recurring()) continue; // ONE_TIME não gera cobranças automáticas
            if (plan.seasonId() == null
                    && ((plan.effectiveFrom() != null && periodDate.isBefore(plan.effectiveFrom()))
                    || (plan.effectiveTo() != null && periodDate.isAfter(plan.effectiveTo())))) {
                continue;
            }
            for (YearMonth chargePeriod : periodsFor(plan, tenantId, period)) {
                LocalDate chargePeriodDate = chargePeriod.atDay(1);
                if ((plan.effectiveFrom() != null && chargePeriodDate.isBefore(plan.effectiveFrom()))
                        || (plan.effectiveTo() != null && chargePeriodDate.isAfter(plan.effectiveTo()))) {
                    continue;
                }
                String billingPeriod = billingPeriod(periodicity, chargePeriod);
                if (billingPeriod == null) continue;
                LocalDate dueDate = dueDate(periodicity, chargePeriod);
                for (ChargeRepository.Target target : charges.findTargets(tenantId, plan.seasonId(), plan.ageGroupId())) {
                    UUID guardianId = isMinor(target.birthDate(), today) ? target.guardianId() : null;
                    if (dryRun) {
                        if (charges.generatedExists(tenantId, plan.id(), target.athleteId(), billingPeriod)) skipped++; else {
                            created++;
                        }
                        continue;
                    }
                    UUID id = UUID.randomUUID();
                    long amountCents = feePlans.overrideAmount(tenantId, target.athleteId(), plan.id(), dueDate, billingPeriod)
                            .orElse(plan.amountCents());
                    if (charges.insertGenerated(id, tenantId, target.athleteId(), guardianId, plan.id(),
                            amountCents, dueDate, billingPeriod, actorId)) {
                        charges.audit(tenantId, actorId, "CHARGE_GENERATED", id);
                        created++;
                    } else {
                        skipped++;
                    }
                }
            }
        }
        return new GenerateView(period.toString(), created, skipped);
    }

    private List<YearMonth> periodsFor(FeePlanRepository.Row plan, UUID tenantId, YearMonth requested) {
        if (plan.seasonId() == null) return List.of(requested);
        FeePlanRepository.SeasonWindow window = feePlans.seasonWindow(tenantId, plan.seasonId()).orElse(null);
        if (window == null || window.startDate() == null || window.endDate() == null) return List.of(requested);
        YearMonth first = YearMonth.from(window.startDate());
        YearMonth last = YearMonth.from(window.endDate());
        List<YearMonth> periods = new ArrayList<>();
        for (YearMonth current = first; !current.isAfter(last); current = current.plusMonths(1)) {
            periods.add(current);
        }
        return periods;
    }

    private static YearMonth parsePeriod(String period) {
        if (period == null || period.isBlank()) return YearMonth.now(ZoneId.of("Europe/Lisbon"));
        try {
            return YearMonth.parse(period.trim());
        } catch (DateTimeParseException e) {
            throw Rules.badRequest("Período inválido (use AAAA-MM)");
        }
    }

    /**
     * Período canónico para o plano, ou {@code null} se este plano não é cobrado nesse mês.
     */
    private static String billingPeriod(FeePeriodicity periodicity, YearMonth period) {
        return switch (periodicity) {
            case MONTHLY -> period.toString();
            case QUARTERLY -> period.getMonthValue() % 3 == 1
                    ? period.getYear() + "-Q" + ((period.getMonthValue() - 1) / 3 + 1) : null;
            case YEARLY -> period.getMonthValue() == 1 ? String.valueOf(period.getYear()) : null;
            case ONE_TIME -> null; // cobrado uma vez, fora da geração automática
        };
    }

    private static LocalDate dueDate(FeePeriodicity periodicity, YearMonth period) {
        return switch (periodicity) {
            case QUARTERLY -> YearMonth.of(period.getYear(),
                    ((period.getMonthValue() - 1) / 3) * 3 + 1).atDay(10);
            case YEARLY -> YearMonth.of(period.getYear(), 1).atDay(10);
            default -> period.atDay(10);
        };
    }

    private static boolean isMinor(LocalDate birthDate, LocalDate today) {
        return birthDate != null && birthDate.plusYears(18).isAfter(today);
    }

    @Transactional
    void updateChargeStatus(CurrentUser user, UUID id, String status) {
        Rules.requireAny(user, Role.BILLING);
        UUID t = Rules.tenant();
        String normalizedStatus = Rules.parse(ChargeStatus.class, status, "Estado").name();
        if (!charges.updateStatus(t, id, normalizedStatus)) throw Rules.notFound("Cobrança não encontrada");
        charges.audit(t, user.id(), "CHARGE_STATUS_UPDATED", id);
    }

    @Transactional
    void deleteCharge(CurrentUser user, UUID id) {
        Rules.requireAny(user, Role.BILLING);
        UUID t = Rules.tenant();
        if (!charges.anonymize(t, id)) throw Rules.notFound("Cobrança não encontrada");
        charges.audit(t, user.id(), "CHARGE_ANONYMIZED", id);
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
        if (charges.findById(t, chargeId).isEmpty()) throw Rules.notFound("Cobrança não encontrada");
        UUID id = UUID.randomUUID();
        payments.insert(id, t, chargeId, paymentMethod, amountCents, reference, user.id());
        payments.audit(t, user.id(), "PAYMENT_CREATED", id);
        // mark charge paid if fully paid? simple: leave status
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
        ledger.audit(t, user.id(), "LEDGER_ENTRY_CREATED", id);
        return new LedgerView(id, normalizedType, category.trim(), amountCents, entryDate, description);
    }

    @Transactional
    void deleteLedger(CurrentUser user, UUID id) {
        Rules.requireAny(user, Role.BILLING);
        UUID t = Rules.tenant();
        if (!ledger.anonymize(t, id)) throw Rules.notFound("Lançamento não encontrado");
        ledger.audit(t, user.id(), "LEDGER_ENTRY_ANONYMIZED", id);
    }
}