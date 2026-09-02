package com.example.ledger.model;

import java.math.BigDecimal;

public class Authorization {
    public enum Status {ACTIVE, SETTLED, REJECTED};
    private final String id;
    private final BigDecimal amount;
    private Status status;

    public Authorization(String id, BigDecimal amount) {
        this.id = id;
        this.amount = amount;
        this.status = Status.ACTIVE;
    }

    public String getId() {
        return id;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }
}
