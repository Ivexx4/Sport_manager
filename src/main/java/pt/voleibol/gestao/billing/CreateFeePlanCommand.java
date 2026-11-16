package pt.voleibol.gestao.billing;

import java.util.UUID;

/** Dados de criação de um plano de quotas (o ator e o clube vêm do pedido autenticado). */
record CreateFeePlanCommand(String name, UUID seasonId, UUID ageGroupId, long amountCents, String periodicity) {
}
