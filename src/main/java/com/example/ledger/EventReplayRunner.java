package com.example.ledger;

import com.example.ledger.model.Account;
import com.example.ledger.model.Authorization;
import com.example.ledger.model.Event;
import com.example.ledger.model.LedgerEntry;
import com.example.ledger.service.LedgerService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class EventReplayRunner {

    public static void main(String[] args) {
        LedgerService service = new LedgerService();
        Set<String> processedEvents = new HashSet<>();

        Account acc001 = new Account("ACC-001", "AED", 2, new BigDecimal("0.00"));
        Account acc002 = new Account("ACC-002", "BHD", 3, new BigDecimal("0.000"));
        service.addAccount(acc001);
        service.addAccount(acc002);
        List<Account> accounts = List.of(acc001, acc002);

        List<Event> events = buildEventStream();

        for (int day = 1; day <= 6; day++) {
            System.out.println("=== Day " + day + " ===");
            LocalDate today = LocalDate.of(2026, 9, day);

            admitEventsForDay(events, processedEvents, service, day);
            closeDay(service, accounts, today, day);

            for (Account acc : accounts) {
                BigDecimal ledgerBalance = service.calculateBalance(acc, today);
                BigDecimal availableBalance = ledgerBalance.subtract(service.activeHoldTotal(acc));

                System.out.println(acc.getId() + " Ledger Balance: " + ledgerBalance.toPlainString());
                System.out.println(acc.getId() + " Available Balance: " + availableBalance.toPlainString());

                for (LedgerEntry entry : acc.getLedgerEntries()) {
                    if (entry.getType() == LedgerEntry.Type.FEE && entry.getValueDate().equals(today)) {
                        System.out.println(acc.getId() + " Fee Assessed: " + entry.getAmount().toPlainString()
                                + " (value_date " + entry.getValueDate() + ")");
                    }
                    if (entry.getType() == LedgerEntry.Type.INTEREST && entry.getValueDate().equals(today)) {
                        System.out.println(acc.getId() + " Interest Capitalized: " + entry.getAmount().toPlainString()
                                + " (value_date " + entry.getValueDate() + ")");
                    }
                }

                System.out.println(acc.getId() + " Authorizations:");
                if (acc.getAuthorizations().isEmpty()) {
                    System.out.println("    (none)");
                } else {
                    for (Map.Entry<String, Authorization> entry : acc.getAuthorizations().entrySet()) {
                        Authorization a = entry.getValue();
                        System.out.println("    " + a.getId() + " " + a.getStatus() + " " + a.getAmount().toPlainString());
                    }
                }
            }

            List<String> allErrors = service.getErrors();
            System.out.println("Errors: " + allErrors);

            System.out.println();
        }
    }

    /**
     * Builds the fixed E1-E10 event stream (E10 generated via splitEqual, not
     * hardcoded). Package-private so EventReplayTest can build a fresh, independent
     * copy of the same stream per test without duplicating the event data.
     */
    static List<Event> buildEventStream() {
        List<Event> events = new ArrayList<>(List.of(
                new Event("E1", 1, Event.Type.CREDIT, "ACC-001", new BigDecimal("1200.00"), LocalDate.of(2026,9,1), null),
                new Event("E2", 1, Event.Type.DEBIT, "ACC-001", new BigDecimal("950.00"), LocalDate.of(2026,9,1), null),
                new Event("E3", 2, Event.Type.AUTHORIZATION, "ACC-001", new BigDecimal("200.00"), LocalDate.of(2026,9,2), "Auth-A"),
                new Event("E4", 3, Event.Type.CREDIT, "ACC-001", new BigDecimal("400.00"), LocalDate.of(2026,9,3), null),
                new Event("E5", 4, Event.Type.SETTLEMENT, "ACC-001", new BigDecimal("185.00"), LocalDate.of(2026,9,4), "Auth-A"),
                new Event("E6", 4, Event.Type.SETTLEMENT, "ACC-001", new BigDecimal("180.00"), LocalDate.of(2026,9,4), "Auth-Z"),
                new Event("E7", 5, Event.Type.DEBIT, "ACC-001", new BigDecimal("620.00"), LocalDate.of(2026,9,2), null),
                new Event("E8", 5, Event.Type.AUTHORIZATION, "ACC-001", new BigDecimal("90.00"), LocalDate.of(2026,9,5), "Auth-B"),
                // E9's amount field is a placeholder only - handleReversal derives the
                // real reversed amount from the original entry (E7) via authId/referenceId.
                new Event("E9", 6, Event.Type.REVERSAL, "ACC-001", new BigDecimal("620.00"), LocalDate.of(2026,9,2), "E7")
        ));

        // E10: BHD 10.000 split into 3 equal instalments, computed - not hardcoded.
        List<BigDecimal> instalments = splitEqual(new BigDecimal("10.000"), 3, 3);
        for (int i = 0; i < instalments.size(); i++) {
            events.add(new Event("E10-" + (i + 1), 5, Event.Type.CREDIT, "ACC-002",
                    instalments.get(i), LocalDate.of(2026,9,5), null));
        }
        return events;
    }

    /**
     * Admits every not-yet-processed event whose own recorded `day` equals `day`.
     * value_date is deliberately NOT consulted here - only later, inside
     * calculateBalance, when deciding which already-applied entries count toward
     * a given day's closing balance. Package-private so tests can drive admission
     * one day at a time.
     */
    static void admitEventsForDay(List<Event> events, Set<String> processed, LedgerService service, int day) {
        for (Event e : events) {
            if (!processed.contains(e.getId()) && e.getDay() == day) {
                service.replayEvents(List.of(e));
                processed.add(e.getId());
            }
        }
    }

    /**
     * Runs day-close rules (fee + interest accrual for every account; interest
     * capitalization only on Day 6). Option A: each day's fee/interest check runs
     * once, at that day's own close, using whatever calculateBalance returns then -
     * never revisited later. See AMBIGUITIES.md.
     */
    static void closeDay(LedgerService service, List<Account> accounts, LocalDate today, int day) {
        for (Account acc : accounts) {
            service.applyOverdraftFee(acc, today);
            service.applyDailyInterest(acc, today);
        }
        if (day == 6) {
            for (Account acc : accounts) {
                service.capitalizeInterest(acc, today);
            }
        }
    }

    /**
     * Splits `total` into `parts` equal instalments at the given decimal scale,
     * such that the instalments sum exactly to `total`. Any leftover smaller than
     * one unit at `scale` is assigned to the LAST instalment(s) - a documented
     * convention (see NUMBERS.md), not a mathematical necessity.
     */
    static List<BigDecimal> splitEqual(BigDecimal total, int parts, int scale) {
        BigDecimal base = total.divide(BigDecimal.valueOf(parts), scale, RoundingMode.DOWN);
        BigDecimal distributed = base.multiply(BigDecimal.valueOf(parts));
        BigDecimal remainder = total.subtract(distributed);
        BigDecimal unit = BigDecimal.ONE.movePointLeft(scale);
        int remainderUnits = remainder.divide(unit).intValueExact();

        List<BigDecimal> result = new ArrayList<>();
        for (int i = 0; i < parts; i++) {
            result.add(base);
        }
        for (int i = 0; i < remainderUnits; i++) {
            int idx = parts - 1 - i;
            result.set(idx, result.get(idx).add(unit));
        }
        return result;
    }
}