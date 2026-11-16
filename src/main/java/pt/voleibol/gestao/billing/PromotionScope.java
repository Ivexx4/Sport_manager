package pt.voleibol.gestao.billing;

/** Alcance de uma promoção de quota: todos os períodos do plano ou um único. */
public enum PromotionScope {

    ALL, SINGLE;

    public static final PromotionScope DEFAULT = ALL;
}
