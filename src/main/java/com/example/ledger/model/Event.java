package com.example.ledger.model;

import java.time.LocalDate;

public class Event {
    public enum Type {CREDIT, DEBIT, AUTHORIZATION, SETTLEMENT, REVERSAL}

    private final String id;
    private final int day;
    private final Type type;
    private final String accountId;
    private final double amount;
    private final LocalDate valueDate;
    private final String authId;

    public Event(String id, int day, Type type, String accountId, double amount, LocalDate valueDate, String authId) {
        this.id = id;
        this.day = day;
        this.type = type;
        this.accountId = accountId;
        this.amount = amount;
        this.valueDate = valueDate;
        this.authId = authId;
    }

    public String getId() {
        return id;
    }

    public int getDay() {
        return day;
    }

    public Type getType() {
        return type;
    }

    public String getAccountId() {
        return accountId;
    }

    public double getAmount() {
        return amount;
    }

    public LocalDate getValueDate() {
        return valueDate;
    }

    public String getAuthId() {
        return authId;
    }
}
