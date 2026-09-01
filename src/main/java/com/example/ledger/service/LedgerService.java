package com.example.ledger.service;

import com.example.ledger.model.Account;
import com.example.ledger.model.Authorization;
import com.example.ledger.model.Event;
import com.example.ledger.model.LedgerEntry;

import java.time.LocalDate;
import java.util.*;

public class LedgerService {
    private final Map<String, Account> accounts = new HashMap<>();
    private final List<String> errors = new ArrayList<>();
    private final Map<String, Double> accruedInterest = new HashMap<>();

    public void addAccount(Account account){
        accounts.put(account.getId(), account);
    }
    public void replayEvents(List<Event> events){
        for (Event event:events){
            processEvent(event);
        }
    }
    private void processEvent(Event event){
        Account account = accounts.get(event.getAccountId());

        if (account == null){
            errors.add("Unknown account for event " + event.getId());
            return;
        }
        switch (event.getType()){
            case CREDIT -> handleCredit(account, event);
            case DEBIT -> handleDebit(account, event);
            case AUTHORIZATION -> handleAuthorization(account, event);
            case SETTLEMENT -> handleSettlement(account, event);
            case REVERSAL -> handleReversal(account, event);
        }
    }
    private void handleCredit(Account account, Event event){
        account.getLedgerEntries().add(
                new LedgerEntry(event.getId(), LedgerEntry.Type.CREDIT, event.getAmount(), event.getValueDate())
        );
    }
    private void handleDebit(Account account, Event event){
        account.getLedgerEntries().add(
                new LedgerEntry(event.getId(), LedgerEntry.Type.DEBIT, event.getAmount(), event.getValueDate())
        );
    }
    private void handleAuthorization(Account account, Event event){
        double availableBalance = calculateBalance(account, event.getValueDate()) - activeHoldTotal(account);

        if (availableBalance - event.getAmount() >= 0){
            account.getAuthorizations().put(event.getAuthId(),
                    new Authorization(event.getAuthId(),event.getAmount()));
        }
        else{
            errors.add("Authorization " + event.getAuthId() + " insufficient funds");
        }
    }
    private void handleSettlement(Account account, Event event){
        Authorization auth = account.getAuthorizations().get(event.getAuthId());

        if (auth == null){
            errors.add("Settlement " + event.getId() + " reference missing authorization " + event.getAuthId());
            return;
        }
        auth.setStatus(Authorization.Status.SETTLED);
        account.getLedgerEntries().add(
                new LedgerEntry(event.getId(), LedgerEntry.Type.SETTLEMENT, -event.getAmount(), event.getValueDate())
        );
    }
    private void handleReversal(Account account, Event event){
        account.getLedgerEntries().add(
                new LedgerEntry(event.getId(), LedgerEntry.Type.REVERSAL, event.getAmount(), event.getValueDate())
        );
    }
    private double calculateBalance(Account account, LocalDate upToDate){
        double balance = account.getOpeningBalance();
        for (LedgerEntry entry : account.getLedgerEntries()){
            if (!entry.getValueDate().isAfter(upToDate))
                balance += entry.getAmount();
        }
        return round(balance, account.getPrecision());
    }
    private double activeHoldTotal(Account account){
        return account.getAuthorizations().values().stream()
                .filter(a -> a.getStatus() == Authorization.Status.ACTIVE)
                .mapToDouble(Authorization::getAmount)
                .sum();
    }
    private double round(double value, int precision){
        double scale = Math.pow(10, precision);
        return Math.round(value * scale) / scale;
    }
    public List<String> getErrors(){
        return errors;
    }
}
