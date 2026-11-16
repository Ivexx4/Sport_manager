package pt.voleibol.gestao.billing;

import java.time.LocalDate;
import java.util.UUID;

/** Dados de criação de uma cobrança manual (o ator e o clube vêm do pedido autenticado). */
record CreateChargeCommand(UUID athleteId, UUID guardianId, UUID feePlanId, long amountCents,
                           LocalDate dueDate, String status) {
}
