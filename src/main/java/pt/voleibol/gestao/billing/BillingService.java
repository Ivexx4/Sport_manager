package pt.voleibol.gestao.billing;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import pt.voleibol.gestao.shared.Rules;

@Service
class BillingService {

    private static final Set<String> WRITERS = Set.of("ADMIN", "DIRECAO", "FINANCEIRO");
    private static final Set<String> PERIODICITIES = Set.of("ONE_TIME", "MONTHLY", "QUARTERLY", "YEARLY");
    private static final Set<String> CHARGE_STATUSES = Set.of("PENDING", "PAID", "OVERDUE", "CANCELLED");
    private static final Set<String> PAYMENT_METHODS = Set.of("CASH", "MULTIBANCO", "MBWAY", "TRANSFER", "OTHER");
    private static final Set<String> LEDGER_TYPES = Set.of("INCOME", "EXPENSE");

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

    private void requireWriter(Set<String> roles) {
        if (roles.stream().noneMatch(WRITERS::contains)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }


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
        return feePlans.list(Rules.tenant()).stream().map(f -> new FeePlanView(f.id(), f.name(), f.seasonId(), f.ageGroupId(), f.amountCents(), f.periodicity())).toList();
    }

    @Transactional
    FeePlanView createFeePlan(UUID actorId, Set<String> roles, String name, UUID seasonId, UUID ageGroupId, long amountCents, String periodicity) {
        requireWriter(roles);
        UUID t = Rules.tenant();
        if (name == null || name.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome obrigatório");
        requireAmount(amountCents);
        String period = periodicity == null ? "MONTHLY" : periodicity;
        requireValue(period, PERIODICITIES, "Periodicidade");
        if (feePlans.nameExists(t, name.trim(), null))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe um plano com este nome");
        UUID id = UUID.randomUUID();
        feePlans.insert(id, t, name.trim(), seasonId, ageGroupId, amountCents, period, actorId);
        feePlans.audit(t, actorId, "FEE_PLAN_CREATED", id);
        return new FeePlanView(id, name.trim(), seasonId, ageGroupId, amountCents, period);
    }

    @Transactional
    void deleteFeePlan(UUID actorId, Set<String> roles, UUID id) {
        requireWriter(roles);
        UUID t = Rules.tenant();
        if (!feePlans.anonymize(t, id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plano não encontrado");
        feePlans.audit(t, actorId, "FEE_PLAN_ANONYMIZED", id);
    }

    @Transactional(readOnly = true)
    List<PromotionView> listPromotions(Set<String> roles, UUID athleteId) {
        requireWriter(roles);
        return feePlans.listPromotions(Rules.tenant(), athleteId).stream()
                .map(p -> new PromotionView(p.id(), p.athleteId(), p.feePlanId(), p.amountCents(),
                        p.validFrom(), p.validTo(), p.reason(), p.active(), p.applicationScope(), p.billingPeriod())).toList();
    }

    @Transactional
    PromotionView createPromotion(UUID actorId, Set<String> roles, UUID athleteId, UUID feePlanId,
                                  long amountCents, LocalDate validFrom, LocalDate validTo, String reason,
                                  String applicationScope, String billingPeriod) {
        requireWriter(roles);
        UUID t = Rules.tenant();
        requireAmount(amountCents);
        if (validFrom == null || (validTo != null && validTo.isBefore(validFrom))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Período da promoção inválido");
        }
        String scope = applicationScope == null ? "ALL" : applicationScope;
        requireValue(scope, Set.of("ALL", "SINGLE"), "Âmbito da promoção");
        if ("SINGLE".equals(scope) && (billingPeriod == null || billingPeriod.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Período obrigatório para promoção de pagamento único");
        }
        if ("SINGLE".equals(scope)) {
            try {
                YearMonth.parse(billingPeriod);
            } catch (DateTimeParseException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Período de pagamento inválido");
            }
        }
        if (!charges.athleteExists(t, athleteId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Atleta não encontrado");
        }
        if (feePlanId != null) {
            feePlans.findById(t, feePlanId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plano não encontrado"));
        }
        UUID id = UUID.randomUUID();
        feePlans.insertPromotion(id, t, athleteId, feePlanId, amountCents, validFrom, validTo, reason,
                scope, "SINGLE".equals(scope) ? billingPeriod : null, actorId);
        return new PromotionView(id, athleteId, feePlanId, amountCents, validFrom, validTo, reason, true,
                scope, "SINGLE".equals(scope) ? billingPeriod : null);
    }

    @Transactional
    void setPromotionActive(UUID actorId, Set<String> roles, UUID id, boolean active) {
        requireWriter(roles);
        if (!feePlans.setPromotionActive(Rules.tenant(), id, active)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Promoção não encontrada");
        }
    }

    // --- charges ---
    @Transactional(readOnly = true)
    List<ChargeView> listCharges(UUID actorId, Set<String> roles, UUID athleteId, String status) {
        boolean writer = roles.contains("ADMIN") || roles.contains("DIRECAO") || roles.contains("FINANCEIRO");
        if (!writer && !roles.contains("ATLETA") && !roles.contains("ENCARREGADO")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return charges.list(Rules.tenant(), actorId, roles, athleteId, status).stream()
                .map(c -> new ChargeView(c.id(), c.athleteId(), c.guardianId(), c.feePlanId(), c.amountCents(), c.dueDate(), c.status(), c.billingPeriod()))
                .toList();
    }

    @Transactional
    ChargeView createCharge(UUID actorId, Set<String> roles, UUID athleteId, UUID guardianId, UUID feePlanId, long amountCents, LocalDate dueDate, String status) {
        requireWriter(roles);
        UUID t = Rules.tenant();
        requireAmount(amountCents);
        if (dueDate == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Data de vencimento obrigatória");
        String chargeStatus = status == null ? "PENDING" : status;
        requireValue(chargeStatus, CHARGE_STATUSES, "Estado");
        if (!charges.athleteExists(t, athleteId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Atleta não encontrado");
        if (guardianId != null && !charges.guardianExists(t, guardianId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Encarregado não encontrado");
        }
        String billingPeriod = null;
        if (feePlanId != null) {
            FeePlanRepository.Row plan = feePlans.findById(t, feePlanId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plano não encontrado"));
            if ("ONE_TIME".equals(plan.periodicity())) {
                billingPeriod = "ONE_TIME";
            }
        }
        UUID id = UUID.randomUUID();
        try {
            charges.insert(id, t, athleteId, guardianId, feePlanId, amountCents, dueDate, chargeStatus, billingPeriod, actorId);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            if ("ONE_TIME".equals(billingPeriod)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta taxa já foi registada para o atleta");
            }
            throw e;
        }
        charges.audit(t, actorId, "CHARGE_CREATED", id);
        return new ChargeView(id, athleteId, guardianId, feePlanId, amountCents, dueDate, chargeStatus, billingPeriod);
    }

    // --- geração automática a partir dos planos ---

    @Transactional
    GenerateView generateCharges(UUID actorId, Set<String> roles, String period, UUID feePlanId, boolean dryRun) {
        requireWriter(roles);
        return generate(Rules.tenant(), actorId, parsePeriod(period), feePlanId, dryRun);
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
        if (feePlanId == null) {
            plans = feePlans.list(tenantId);
        } else {
            plans = List.of(feePlans.findById(tenantId, feePlanId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plano não encontrado")));
        }
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Lisbon"));
        int created = 0;
        int skipped = 0;
        for (FeePlanRepository.Row plan : plans) {
            LocalDate periodDate = period.atDay(1);
            if (!plan.active()) {
                continue;
            }
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
                String billingPeriod = billingPeriod(plan.periodicity(), chargePeriod);
                if (billingPeriod == null) {
                    continue;
                }
                LocalDate dueDate = dueDate(plan.periodicity(), chargePeriod);
                for (ChargeRepository.Target target : charges.findTargets(tenantId, plan.seasonId(), plan.ageGroupId())) {
                    UUID guardianId = isMinor(target.birthDate(), today) ? target.guardianId() : null;
                    if (dryRun) {
                        if (charges.generatedExists(tenantId, plan.id(), target.athleteId(), billingPeriod)) {
                            skipped++;
                        } else {
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
        if (plan.seasonId() == null) {
            return List.of(requested);
        }
        FeePlanRepository.SeasonWindow window = feePlans.seasonWindow(tenantId, plan.seasonId()).orElse(null);
        if (window == null || window.startDate() == null || window.endDate() == null) {
            return List.of(requested);
        }
        YearMonth first = YearMonth.from(window.startDate());
        YearMonth last = YearMonth.from(window.endDate());
        List<YearMonth> periods = new ArrayList<>();
        for (YearMonth current = first; !current.isAfter(last); current = current.plusMonths(1)) {
            periods.add(current);
        }
        return periods;
    }

    private static YearMonth parsePeriod(String period) {
        if (period == null || period.isBlank()) {
            return YearMonth.now(ZoneId.of("Europe/Lisbon"));
        }
        try {
            return YearMonth.parse(period.trim());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Período inválido (use AAAA-MM)");
        }
    }

    /**
     * Período canónico para o plano, ou {@code null} se este plano não é cobrado nesse mês.
     */
    private static String billingPeriod(String periodicity, YearMonth period) {
        return switch (periodicity) {
            case "MONTHLY" -> period.toString();
            case "QUARTERLY" -> period.getMonthValue() % 3 == 1
                    ? period.getYear() + "-Q" + ((period.getMonthValue() - 1) / 3 + 1) : null;
            case "YEARLY" -> period.getMonthValue() == 1 ? String.valueOf(period.getYear()) : null;
            default -> null; // ONE_TIME não gera cobranças automáticas
        };
    }

    private static LocalDate dueDate(String periodicity, YearMonth period) {
        return switch (periodicity) {
            case "QUARTERLY" -> YearMonth.of(period.getYear(),
                    ((period.getMonthValue() - 1) / 3) * 3 + 1).atDay(10);
            case "YEARLY" -> YearMonth.of(period.getYear(), 1).atDay(10);
            default -> period.atDay(10);
        };
    }

    private static boolean isMinor(LocalDate birthDate, LocalDate today) {
        return birthDate != null && birthDate.plusYears(18).isAfter(today);
    }

    @Transactional
    void updateChargeStatus(UUID actorId, Set<String> roles, UUID id, String status) {
        requireWriter(roles);
        UUID t = Rules.tenant();
        requireValue(status, CHARGE_STATUSES, "Estado");
        if (!charges.updateStatus(t, id, status))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cobrança não encontrada");
        charges.audit(t, actorId, "CHARGE_STATUS_UPDATED", id);
    }

    @Transactional
    void deleteCharge(UUID actorId, Set<String> roles, UUID id) {
        requireWriter(roles);
        UUID t = Rules.tenant();
        if (!charges.anonymize(t, id))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cobrança não encontrada");
        charges.audit(t, actorId, "CHARGE_ANONYMIZED", id);
    }

    // --- payments ---
    @Transactional(readOnly = true)
    List<PaymentView> listPayments(Set<String> roles, UUID chargeId) {
        requireWriter(roles);
        UUID t = Rules.tenant();
        if (charges.findById(t, chargeId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cobrança não encontrada");
        }
        return payments.list(t, chargeId).stream().map(p -> new PaymentView(p.id(), p.chargeId(), p.method(), p.amountCents(), p.reference(), p.paidAt())).toList();
    }

    @Transactional
    PaymentView createPayment(UUID actorId, Set<String> roles, UUID chargeId, String method, long amountCents, String reference) {
        requireWriter(roles);
        UUID t = Rules.tenant();
        requireAmount(amountCents);
        String paymentMethod = method == null ? "CASH" : method;
        requireValue(paymentMethod, PAYMENT_METHODS, "Método");
        if (charges.findById(t, chargeId).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cobrança não encontrada");
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
        return ledger.list(Rules.tenant()).stream().map(l -> new LedgerView(l.id(), l.type(), l.category(), l.amountCents(), l.entryDate(), l.description())).toList();
    }

    @Transactional
    LedgerView createLedger(UUID actorId, Set<String> roles, String type, String category, long amountCents, LocalDate entryDate, String description) {
        requireWriter(roles);
        UUID t = Rules.tenant();
        requireAmount(amountCents);
        requireValue(type, LEDGER_TYPES, "Tipo");
        if (category == null || category.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Categoria obrigatória");
        if (entryDate == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Data obrigatória");
        UUID id = UUID.randomUUID();
        ledger.insert(id, t, type, category.trim(), amountCents, entryDate, description, actorId);
        ledger.audit(t, actorId, "LEDGER_ENTRY_CREATED", id);
        return new LedgerView(id, type, category.trim(), amountCents, entryDate, description);
    }

    @Transactional
    void deleteLedger(UUID actorId, Set<String> roles, UUID id) {
        requireWriter(roles);
        UUID t = Rules.tenant();
        if (!ledger.anonymize(t, id))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Lançamento não encontrado");
        ledger.audit(t, actorId, "LEDGER_ENTRY_ANONYMIZED", id);
    }
}