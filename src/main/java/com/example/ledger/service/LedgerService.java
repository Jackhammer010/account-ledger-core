package com.example.ledger.service;

import com.example.ledger.model.Account;
import com.example.ledger.model.Authorization;
import com.example.ledger.model.Event;
import com.example.ledger.model.LedgerEntry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

public class LedgerService {
    private final Map<String, Account> accounts = new HashMap<>();
    private final List<String> errors = new ArrayList<>();
    private final Map<String, BigDecimal> accruedInterest = new HashMap<>();
    private final Map<String, Set<String>> reversedEntryIds = new HashMap<>();

    public void addAccount(Account account){
        accounts.put(account.getId(), account);
    }
    public void applyDailyInterest(Account account, LocalDate date) {
        BigDecimal balance = calculateBalance(account, date);
        if (balance.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal interest = round(
                    balance.multiply(new BigDecimal("0.0004")),
                    account.getPrecision()
            );
            accruedInterest.merge(account.getId(), interest, BigDecimal::add);
        }
    }
    public void capitalizeInterest(Account account, LocalDate date) {
        BigDecimal total = accruedInterest.getOrDefault(account.getId(), BigDecimal.ZERO);
        if (total.compareTo(BigDecimal.ZERO) > 0) {
            account.getLedgerEntries().add(
                    new LedgerEntry("INT-" + date, LedgerEntry.Type.INTEREST, total, date)
            );
            accruedInterest.put(account.getId(), BigDecimal.ZERO); // reset
        }
    }
    public void replayEvents(List<Event> events){
        for (Event event:events){
            processEvent(event);
        }
    }
    public void applyOverdraftFee(Account account, LocalDate date) {
        BigDecimal balance = calculateBalance(account, date);
        if (balance.compareTo(BigDecimal.ZERO) < 0) {
            account.getLedgerEntries().add(
                    new LedgerEntry("FEE-" + date, LedgerEntry.Type.FEE, new BigDecimal("-25.00"), date)
            );
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
                new LedgerEntry(event.getId(), LedgerEntry.Type.DEBIT, event.getAmount().negate(), event.getValueDate())
        );
    }
    private void handleAuthorization(Account account, Event event){
        if (account.getAuthorizations().containsKey(event.getAuthId())){
            errors.add("Authorization " + event.getAuthId() + " already exists");
            return;
        }
        BigDecimal availableBalance = calculateBalance(account, event.getValueDate())
                .subtract(activeHoldTotal(account));

        if (availableBalance.subtract(event.getAmount()).compareTo(BigDecimal.ZERO) >= 0){
            account.getAuthorizations().put(event.getAuthId(),
                    new Authorization(event.getAuthId(),event.getAmount()));
        }
        else{
            Authorization rejected = new Authorization(event.getAuthId(), event.getAmount());
            rejected.setStatus(Authorization.Status.REJECTED);
            account.getAuthorizations().put(event.getAuthId(), rejected);
            errors.add("Authorization " + event.getAuthId() + " insufficient funds");
        }
    }
    private void handleSettlement(Account account, Event event){
        Authorization auth = account.getAuthorizations().get(event.getAuthId());
        if (auth == null){
            errors.add("Settlement " + event.getId() + " references missing authorization " + event.getAuthId());
            return;
        }
        if (auth.getStatus() != Authorization.Status.ACTIVE){
            errors.add("Settlement " + event.getId() + " references non-active authorization " + event.getAuthId());
            return;
        }
        auth.setStatus(Authorization.Status.SETTLED);
        account.getLedgerEntries().add(
                new LedgerEntry(event.getId(), LedgerEntry.Type.SETTLEMENT, event.getAmount().negate(), event.getValueDate())
        );
    }
    private void handleReversal(Account account, Event event){
        String referenceId = event.getAuthId();

        LedgerEntry original = account.getLedgerEntries().stream()
                .filter(e -> e.getId().equals(referenceId))
                .findFirst()
                .orElse(null);

        if (original == null){
            errors.add("Reversal " + event.getId() + " references missing entry " + referenceId);
            return;
        }

        Set<String> reversed = reversedEntryIds.computeIfAbsent(account.getId(), k -> new HashSet<>());

        if (reversed.contains(referenceId)){
            errors.add("Reversal " + event.getId() + " — entry " + referenceId + " already reversed");
            return;
        }

        account.getLedgerEntries().add(
                new LedgerEntry(event.getId(), LedgerEntry.Type.REVERSAL, original.getAmount().negate(), event.getValueDate())
        );
        reversed.add(referenceId);
    }
    public BigDecimal calculateBalance(Account account, LocalDate upToDate){
        BigDecimal balance = account.getOpeningBalance();
        for (LedgerEntry entry : account.getLedgerEntries()){
            if (!entry.getValueDate().isAfter(upToDate))
                balance = balance.add(entry.getAmount());
        }
        return balance.setScale(account.getPrecision(), RoundingMode.HALF_UP);
    }
    public BigDecimal activeHoldTotal(Account account){
        return account.getAuthorizations().values().stream()
                .filter(a -> a.getStatus() == Authorization.Status.ACTIVE)
                .map(Authorization::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    private BigDecimal round(BigDecimal value, int precision){
        return value.setScale(precision, RoundingMode.HALF_UP);
    }
    public List<String> getErrors(){
        return errors;
    }
}
