package pt.voleibol.gestao.billing;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import pt.voleibol.gestao.shared.AuditLog;
import pt.voleibol.gestao.shared.Rules;

/**
 * Geração de cobranças a partir dos planos do clube: períodos, vencimentos, menores e promoções.
 *
 * <p>Fica fora do {@link BillingService} para o cálculo não se misturar com o CRUD; o serviço
 * continua a expor as duas entradas (API e agendador) e delega aqui. O caminho de simulação
 * ({@code dryRun}) só conta o que aconteceria; o de gravação insere e audita cada cobrança criada.
 *
 * <p>As leituras que não dependem do período (atletas elegíveis) ou que servem todos os atletas do
 * plano (promoções) fazem-se uma única vez, fora dos ciclos.
 */
class ChargePlanner {

    /** Contagem de um passo da geração: o que seria (ou foi) criado e o que já existia. */
    private record Tally(int created, int skipped) {

        static final Tally ZERO = new Tally(0, 0);

        Tally plus(Tally other) {
            return new Tally(created + other.created(), skipped + other.skipped());
        }
    }

    private final FeePlanRepository feePlans;
    private final ChargeRepository charges;
    private final AuditLog audit;
    private final Clock clock;

    ChargePlanner(FeePlanRepository feePlans, ChargeRepository charges, AuditLog audit, Clock clock) {
        this.feePlans = feePlans;
        this.charges = charges;
        this.audit = audit;
        this.clock = clock;
    }

    BillingService.GenerateView generate(UUID tenantId, UUID actorId, YearMonth period, UUID feePlanId,
                                         boolean dryRun) {
        LocalDate today = LocalDate.now(clock);
        Tally total = Tally.ZERO;
        for (FeePlanRepository.Row plan : plansOf(tenantId, feePlanId)) {
            if (!plan.active()) continue;
            FeePeriodicity periodicity = Rules.find(FeePeriodicity.class, plan.periodicity())
                    .orElse(FeePeriodicity.DEFAULT);
            if (!periodicity.recurring()) continue; // ONE_TIME não gera cobranças automáticas
            List<ChargeRepository.Target> targets =
                    charges.findTargets(tenantId, plan.seasonId(), plan.ageGroupId());
            if (targets.isEmpty()) continue;
            for (YearMonth chargePeriod : periodsFor(plan, tenantId, period)) {
                if (!withinWindow(plan, chargePeriod)) continue;
                String billingPeriod = billingPeriod(periodicity, chargePeriod);
                if (billingPeriod == null) continue;
                total = total.plus(dryRun
                        ? simulate(tenantId, plan, targets, billingPeriod)
                        : persist(tenantId, actorId, plan, targets, billingPeriod,
                                dueDate(periodicity, chargePeriod), today));
            }
        }
        return new BillingService.GenerateView(period.toString(), total.created(), total.skipped());
    }

    /** Simulação: responde o mesmo que a gravação, sem escrever nem auditar. */
    private Tally simulate(UUID tenantId, FeePlanRepository.Row plan, List<ChargeRepository.Target> targets,
                           String billingPeriod) {
        int created = 0;
        int skipped = 0;
        for (ChargeRepository.Target target : targets) {
            if (charges.generatedExists(tenantId, plan.id(), target.athleteId(), billingPeriod)) skipped++;
            else created++;
        }
        return new Tally(created, skipped);
    }

    /** Gravação: as promoções do plano e período são lidas de uma vez para todos os atletas. */
    private Tally persist(UUID tenantId, UUID actorId, FeePlanRepository.Row plan,
                          List<ChargeRepository.Target> targets, String billingPeriod, LocalDate dueDate,
                          LocalDate today) {
        Map<UUID, Long> overrides = feePlans.overrideAmounts(tenantId, plan.id(), dueDate, billingPeriod,
                targets.stream().map(ChargeRepository.Target::athleteId).toList());
        int created = 0;
        int skipped = 0;
        for (ChargeRepository.Target target : targets) {
            UUID guardianId = isMinor(target.birthDate(), today) ? target.guardianId() : null;
            long amountCents = overrides.getOrDefault(target.athleteId(), plan.amountCents());
            UUID id = UUID.randomUUID();
            if (charges.insertGenerated(id, tenantId, target.athleteId(), guardianId, plan.id(), amountCents,
                    dueDate, billingPeriod, actorId)) {
                audit.record(tenantId, actorId, "CHARGE_GENERATED", "charge", id);
                created++;
            } else {
                skipped++;
            }
        }
        return new Tally(created, skipped);
    }

    private List<FeePlanRepository.Row> plansOf(UUID tenantId, UUID feePlanId) {
        if (feePlanId == null) return feePlans.list(tenantId);
        return List.of(feePlans.findById(tenantId, feePlanId)
                .orElseThrow(() -> Rules.notFound("Plano não encontrado")));
    }

    /** Meses a faturar: os da época do plano ou apenas o pedido, quando o plano não tem época. */
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

    /** Vigência do plano: um único teste, usado por todos os períodos faturados. */
    private static boolean withinWindow(FeePlanRepository.Row plan, YearMonth period) {
        LocalDate first = period.atDay(1);
        return (plan.effectiveFrom() == null || !first.isBefore(plan.effectiveFrom()))
                && (plan.effectiveTo() == null || !first.isAfter(plan.effectiveTo()));
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
}
