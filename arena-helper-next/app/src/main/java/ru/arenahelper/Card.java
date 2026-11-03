package ru.arenahelper;


public final class Card {
    private final String name;
    private final Integer score;

    public Card(String str, Integer num) {
        this.name = str;
        this.score = num;
    }

    public String getName() {
        return this.name;
    }

    public Integer getScore() {
        return this.score;
    }
}
