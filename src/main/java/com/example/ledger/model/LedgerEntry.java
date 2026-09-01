package com.example.ledger.model;

import java.time.LocalDate;

public class LedgerEntry {
    public enum Type {CREDIT, DEBIT, FEE, INTEREST, SETTLEMENT, REVERSAL}

    private final String id;
    private final Type type;
    private final double amount;
    private final LocalDate valueDate;

    public LedgerEntry(String id, Type type, double amount, LocalDate valueDate) {
        this.id = id;
        this.type = type;
        this.amount = amount;
        this.valueDate = valueDate;
    }

    public String getId() {
        return id;
    }

    public Type getType() {
        return type;
    }

    public double getAmount() {
        return amount;
    }

    public LocalDate getValueDate() {
        return valueDate;
    }
}
