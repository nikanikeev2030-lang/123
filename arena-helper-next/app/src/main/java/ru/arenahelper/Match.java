package ru.arenahelper;


public final class Match {
    private final Card card;
    private final double confidence;

    public Match(Card card, double d) {
        this.card = card;
        this.confidence = d;
    }

    public Card getCard() {
        return this.card;
    }

    public double getConfidence() {
        return this.confidence;
    }
}
