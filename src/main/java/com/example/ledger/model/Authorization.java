package com.example.ledger.model;

public class Authorization {
    public enum Status {ACTIVE, SETTLED, REJECTED};
    private final String id;
    private final double amount;
    private Status status;

    public Authorization(String id, double amount) {
        this.id = id;
        this.amount = amount;
        this.status = Status.ACTIVE;
    }

    public String getId() {
        return id;
    }

    public double getAmount() {
        return amount;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }
}
