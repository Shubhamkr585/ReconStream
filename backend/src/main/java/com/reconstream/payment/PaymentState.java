package com.reconstream.payment;

public enum PaymentState {
    INITIATED(0),
    PENDING(1),
    AUTHORIZED(2),
    CAPTURED(3),
    SETTLED(4),
    FAILED(-1),
    REFUNDED(-2);

    private final int precedence;

    PaymentState(int precedence) {
        this.precedence = precedence;
    }

    public int getPrecedence() {
        return precedence;
    }

    public enum TransitionResult {
        VALID,
        STALE_OR_DUPLICATE,
        INVALID
    }

    public TransitionResult evaluateTransition(PaymentState target) {
        if (target == null) {
            return TransitionResult.INVALID;
        }

        if (this == target) {
            return TransitionResult.STALE_OR_DUPLICATE;
        }

        return switch (this) {
            case INITIATED -> switch (target) {
                case PENDING, AUTHORIZED, CAPTURED, FAILED -> TransitionResult.VALID;
                default -> TransitionResult.INVALID;
            };
            case PENDING -> switch (target) {
                case AUTHORIZED, CAPTURED, FAILED -> TransitionResult.VALID;
                case INITIATED -> TransitionResult.STALE_OR_DUPLICATE;
                default -> TransitionResult.INVALID;
            };
            case AUTHORIZED -> switch (target) {
                case CAPTURED, FAILED -> TransitionResult.VALID;
                case INITIATED, PENDING -> TransitionResult.STALE_OR_DUPLICATE;
                default -> TransitionResult.INVALID;
            };
            case CAPTURED -> switch (target) {
                case SETTLED, REFUNDED -> TransitionResult.VALID;
                case INITIATED, PENDING, AUTHORIZED -> TransitionResult.STALE_OR_DUPLICATE;
                default -> TransitionResult.INVALID;
            };
            case SETTLED -> switch (target) {
                case REFUNDED -> TransitionResult.VALID;
                case INITIATED, PENDING, AUTHORIZED, CAPTURED -> TransitionResult.STALE_OR_DUPLICATE;
                default -> TransitionResult.INVALID;
            };
            case FAILED, REFUNDED -> TransitionResult.STALE_OR_DUPLICATE;
        };
    }
}
