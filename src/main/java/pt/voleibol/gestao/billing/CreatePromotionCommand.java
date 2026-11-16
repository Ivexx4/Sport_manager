package pt.voleibol.gestao.billing;

import java.time.LocalDate;
import java.util.UUID;

/** Dados de criação de uma promoção (valor próprio) para um atleta (o ator vem do pedido autenticado). */
record CreatePromotionCommand(UUID athleteId, UUID feePlanId, long amountCents, LocalDate validFrom,
                              LocalDate validTo, String reason, String applicationScope, String billingPeriod) {
}
