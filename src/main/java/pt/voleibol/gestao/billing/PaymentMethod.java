package pt.voleibol.gestao.billing;

/** Método de pagamento de uma cobrança ({@code payment.method}). */
public enum PaymentMethod {

    CASH, MULTIBANCO, MBWAY, TRANSFER, OTHER;

    public static final PaymentMethod DEFAULT = CASH;
}
