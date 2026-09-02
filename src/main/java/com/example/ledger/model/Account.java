package com.example.ledger.model;


import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Account {
    private final String id;
    private final String currency;
    private final int precision;
    private final BigDecimal openingBalance;

    private final List<LedgerEntry> ledgerEntries = new ArrayList<>();
    private final Map<String, Authorization> authorizations = new HashMap<>();

    public Account(String id, String currency, int precision, BigDecimal openingBalance){
        this.id = id;
        this.currency = currency;
        this.precision = precision;
        this.openingBalance = openingBalance;
    }

    public String getId() {
        return id;
    }

    public String getCurrency() {
        return currency;
    }

    public int getPrecision() {
        return precision;
    }

    public BigDecimal getOpeningBalance() {
        return openingBalance;
    }

    public List<LedgerEntry> getLedgerEntries() {
        return ledgerEntries;
    }

    public Map<String, Authorization> getAuthorizations() {
        return authorizations;
    }
}
