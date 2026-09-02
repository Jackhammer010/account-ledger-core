package com.example.ledger;

import com.example.ledger.model.Account;
import com.example.ledger.model.Authorization;
import com.example.ledger.model.Event;
import com.example.ledger.model.LedgerEntry;
import com.example.ledger.service.LedgerService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class EventReplayTest {

    /** Fresh service + accounts + event stream, admitted only through `throughDay` (inclusive).
     *  Day-close rules (fee/interest/capitalize) are NOT applied - call closeDaysUpTo() separately
     *  when a test needs to control exactly when the close step runs relative to an assertion. */
    private static class Fixture {
        LedgerService service = new LedgerService();
        Account acc001 = new Account("ACC-001", "AED", 2, new BigDecimal("0.00"));
        Account acc002 = new Account("ACC-002", "BHD", 3, new BigDecimal("0.000"));
        List<Account> accounts = List.of(acc001, acc002);
        List<Event> events = EventReplayRunner.buildEventStream();
        Set<String> processed = new HashSet<>();

        Fixture() {
            service.addAccount(acc001);
            service.addAccount(acc002);
        }

        void admitThroughDay(int day) {
            for (int d = 1; d <= day; d++) {
                EventReplayRunner.admitEventsForDay(events, processed, service, d);
            }
        }

        void closeDaysUpTo(int day) {
            for (int d = 1; d <= day; d++) {
                EventReplayRunner.closeDay(service, accounts, LocalDate.of(2026, 9, d), d);
            }
        }

        /** Full simulation through `day`: admits and closes each day in the correct
         *  interleaved order (admit day N, then close day N, then admit day N+1, ...). */
        void runThroughDay(int day) {
            for (int d = 1; d <= day; d++) {
                EventReplayRunner.admitEventsForDay(events, processed, service, d);
                EventReplayRunner.closeDay(service, accounts, LocalDate.of(2026, 9, d), d);
            }
        }
    }

    // --- Acceptance criterion 1: TRUE. Day 2 closing balance, evaluated at end of
    // Day 5, before that day's fee is assessed, is AED -370.00. ---
    @Test
    void day2ClosingBalance_atEndOfDay5_beforeFee_isMinus370() {
        Fixture f = new Fixture();
        f.runThroughDay(4);   // days 1-4: admit and close interleaved, in order - matches the real runner
        f.admitThroughDay(5); // admits Day 5's events (E7, E8) but does NOT run Day 5's close

        BigDecimal day2Balance = f.service.calculateBalance(f.acc001, LocalDate.of(2026, 9, 2));
        assertEquals(new BigDecimal("-370.00"), day2Balance);
    }

    // --- Acceptance criterion 2: FALSE as stated. E7 does cause exactly one fee under
    // this design (Option A - each day evaluated once, at its own close), but it is
    // dated Day 5 (when the negative balance was discovered), not Day 2 (E7's value_date).
    // See REJECTED.md for the full argument against "on Day 2". ---
    @Test
    void e7CausesExactlyOneFee_datedDay5_notDay2() {
        Fixture f = new Fixture();
        f.runThroughDay(5);

        List<LedgerEntry> fees = f.acc001.getLedgerEntries().stream()
                .filter(e -> e.getType() == LedgerEntry.Type.FEE)
                .toList();

        assertEquals(1, fees.size(), "expected exactly one fee entry");
        assertEquals(LocalDate.of(2026, 9, 5), fees.get(0).getValueDate(),
                "fee should be dated Day 5 (discovery day under Option A), not Day 2 (E7's value_date)");
        assertEquals(new BigDecimal("-25.00"), fees.get(0).getAmount());
    }

    // --- Acceptance criterion 3: TRUE. Auth-A's Day 4 settlement (185.00, less than
    // the 200.00 hold) is accepted - partial settlement is valid. ---
    @Test
    void authASettlementOnDay4_isAccepted() {
        Fixture f = new Fixture();
        f.runThroughDay(4);

        assertEquals(Authorization.Status.SETTLED, f.acc001.getAuthorizations().get("Auth-A").getStatus());
        assertTrue(f.acc001.getLedgerEntries().stream().anyMatch(e -> e.getId().equals("E5")));
        assertTrue(f.service.getErrors().stream().noneMatch(err -> err.contains("E5")));
    }

    // --- Acceptance criterion 4: TRUE. E6 (settlement referencing Auth-Z, which was
    // never authorized) is rejected; no funds move. ---
    @Test
    void settlementWithMissingAuthorization_isRejected_fundsDoNotMove() {
        Fixture f = new Fixture();
        f.runThroughDay(4);

        assertTrue(f.service.getErrors().stream().anyMatch(e -> e.contains("Auth-Z")));
        assertTrue(f.acc001.getLedgerEntries().stream().noneMatch(e -> e.getId().equals("E6")),
                "no ledger entry should exist for the rejected settlement E6");
    }

    // --- Corollary of criterion 5, and the actual outcome for Auth-B: by the time E8
    // is processed (Day 5, after E7's backdated debit has already posted), available
    // balance is already -155.00. Applying a 90.00 hold keeps it negative, so Auth-B
    // is rejected outright - "if approved" (criterion 5) never fires here. ---
    @Test
    void authB_isRejected_dueToInsufficientAvailableBalance() {
        Fixture f = new Fixture();
        f.runThroughDay(5);

        assertEquals(Authorization.Status.REJECTED, f.acc001.getAuthorizations().get("Auth-B").getStatus());
        assertTrue(f.service.getErrors().stream().anyMatch(e -> e.contains("Auth-B")));
        assertEquals(0, BigDecimal.ZERO.compareTo(f.service.activeHoldTotal(f.acc001)));
    }

    // --- Acceptance criterion 7: FALSE. Three equal instalments of 3.334 would sum
    // to 10.002, not 10.000. The correct split has two instalments at 3.333 and one
    // (the last, by convention - see NUMBERS.md) at 3.334. ---
    @Test
    void bhdInstalments_sumExactly_andAreNotAllEqual() {
        List<BigDecimal> instalments = EventReplayRunner.splitEqual(new BigDecimal("10.000"), 3, 3);

        BigDecimal sum = instalments.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("10.000"), sum);

        assertEquals(List.of(new BigDecimal("3.333"), new BigDecimal("3.333"), new BigDecimal("3.334")), instalments);
        assertFalse(instalments.stream().allMatch(i -> i.equals(new BigDecimal("3.334"))),
                "criterion 7 claims all three are 3.334 - that would sum to 10.002, not 10.000");
    }

    // --- Acceptance criterion 8: inapplicable by construction. The capitalized total
    // is DEFINED as the sum of the already-rounded daily accruals (see LedgerService
    // .applyDailyInterest / .capitalizeInterest) - there is no independently-computed
    // unrounded total to reconcile against, so no remainder ever exists to discard. ---
    @Test
    void capitalizedInterest_equalsExactSumOfRoundedDailyAccruals() {
        Fixture f = new Fixture();
        f.runThroughDay(6);

        List<LedgerEntry> interestEntries = f.acc001.getLedgerEntries().stream()
                .filter(e -> e.getType() == LedgerEntry.Type.INTEREST)
                .toList();

        assertEquals(1, interestEntries.size());
        assertEquals(new BigDecimal("0.83"), interestEntries.get(0).getAmount());
    }

    @Test
    void acc002_bhdInterestCapitalized_isCorrect() {
        Fixture f = new Fixture();
        f.runThroughDay(6);

        List<LedgerEntry> interestEntries = f.acc002.getLedgerEntries().stream()
                .filter(e -> e.getType() == LedgerEntry.Type.INTEREST)
                .toList();

        assertEquals(1, interestEntries.size());
        assertEquals(new BigDecimal("0.008"), interestEntries.get(0).getAmount());
    }

    // ============================================================================
    // FAILING TEST - INTENTIONAL. Do not "fix" this by changing production code.
    //
    // Acceptance criterion 6 claims: "After E9, all balances and fees return to
    // their pre-E7 values." This test encodes that claim literally and asserts it
    // against the account's Day 6 ledger balance (before interest, so fees are
    // isolated from the unrelated interest calculation).
    //
    // It FAILS. Pre-E7, Day 6's balance would have been 465.00 (1200-950+400-185).
    // Post-E9, it is 440.00 - short by exactly 25.00, the overdraft fee that was
    // assessed on Day 5 while E7 was live on the books.
    //
    // What this reveals: E9 only reverses E7 itself (a -620.00/+620.00 offsetting
    // pair). It does not - and, under this design, cannot - reverse the FEE that
    // E7's temporary negative balance triggered as a side effect, because nothing
    // in the event stream issues a reversal against the fee entry, and the ledger
    // is append-only (no entry is ever deleted or mutated to "undo" it). A reversal
    // only ever cancels the specific entry it names.
    //
    // This is why criterion 6 is rejected in REJECTED.md - not narrowly ("off by a
    // rounding cent") but structurally: reversing a cause does not retroactively
    // reverse a consequence that has already been separately booked.
    // ============================================================================
    @Test
    void FAILING_afterE9_balanceDoesNotFullyReturnToPreE7Value() {
        Fixture f = new Fixture();
        f.runThroughDay(5); // through Day 5 close (fee assessed), before interest capitalizes

        BigDecimal preE7Day6Baseline = new BigDecimal("465.00"); // 1200 - 950 + 400 - 185, no E7/fee/reversal
        BigDecimal actualBalance = f.service.calculateBalance(f.acc001, LocalDate.of(2026, 9, 6));

        assertEquals(preE7Day6Baseline, actualBalance,
                "criterion 6 asserts this holds; it does not - see class-level comment above");
    }
}