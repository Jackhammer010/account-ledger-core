# REJECTED.md

Every acceptance criterion was independently verified — either
symbolically, against the actual `LedgerService` logic, or numerically,
against a parallel Python/Decimal reimplementation of the same
algorithm (see WORKLOG.md) — before being accepted or refused here.
None of the following was rejected on a hunch.

## Criteria refused

### "E7 causes exactly one overdraft fee to be assessed, on Day 2." — **REFUSED, partially.**

The "exactly one fee" part is correct under this build's design. The
**"on Day 2" part is wrong.**

This codebase evaluates each day's closing balance once, at that day's
own close, using whatever is in the ledger at that point in the event
stream (Option A — see AMBIGUITIES.md for the full argument for this
reading). E7 is recorded on Day 5 with `value_date` Day 2. Under
Option A, Day 2 already closed — clean, at 250.00 — three processing
days before E7 exists. E7's negative effect is only ever seen on **Day
5's** close, when the balance evaluates to −155.00 before the fee and
−180.00 after. The single fee this build produces is booked with
`value_date` **Day 5**, not Day 2.

This is confirmed directly in `EventReplayTest`:
```java
assertEquals(LocalDate.of(2026, 9, 5), fees.get(0).getValueDate(),
    "fee should be dated Day 5 (discovery day under Option A), not Day 2 (E7's value_date)");
```

A design that instead re-evaluates every prior day's balance whenever a
backdated entry lands (Option B, scoped but not built — see "Approaches
abandoned" below) would date this fee to Day 2, matching the criterion
on *that* count — but would then also produce fees on Day 4 and Day 5
as the same backdated debit cascades forward through the running
balance, which breaks "exactly one." No design considered satisfies
both halves of this criterion simultaneously; it is refused as stated
under either interpretation.

### "After E9, all balances and fees return to their pre-E7 values." — **REFUSED.**

Balances alone would return to their pre-E7 value — E9 posts `+620.00`
at `value_date` Day 2, exactly offsetting E7's `−620.00` at the same
`value_date`, so the raw E7/E9 pair nets to zero. But the criterion
claims *fees* return too, and they don't: the Day 5 overdraft fee
(`−25.00`) was already posted, as its own independent ledger entry,
before E9 ever arrives (E9 is Day 6). The ledger is append-only —
nothing in the event stream issues a reversal against the *fee* entry,
only against E7. Reversing a cause does not retroactively reverse a
consequence that has already been separately booked.

Net effect: post-E9, Day 6's balance is 440.00 (pre-interest), not the
465.00 it would have been had E7 never existed (`1200 − 950 + 400 −
185`). Off by exactly the one unreversed fee.

This is the codebase's required intentional failing test —
`EventReplayTest.FAILING_afterE9_balanceDoesNotFullyReturnToPreE7Value`
— which asserts this criterion literally and fails with
`expected: <465.00> but was: <-180.00>` (measured before Day 6's
interest capitalization, to isolate the fee discrepancy from an
unrelated calculation). See that test's inline comment for the
verbatim version of this argument.

### "The three BHD instalments in E10 must each be BHD 3.334." — **REFUSED.**

Arithmetically impossible: `3.334 × 3 = 10.002`, not `10.000`. Three
*equal* instalments summing to a total that isn't itself evenly
divisible by 3 at 3 decimal places cannot all be the same value — some
instalment has to absorb the leftover `0.001`. This build's actual
split is `[3.333, 3.333, 3.334]` (remainder assigned to the last
instalment, by documented convention — see NUMBERS.md §9), which sums
correctly to `10.000`.

Confirmed directly:
```java
assertEquals(List.of(new BigDecimal("3.333"), new BigDecimal("3.333"), new BigDecimal("3.334")), instalments);
```

### "If the rounded daily interest accruals do not sum to the capitalized total, the remainder is discarded." — **REFUSED.**

This directly contradicts the spec's own non-negotiable rule two
sentences earlier: "the rounded daily accruals must sum exactly to the
capitalized total." A design that discards a remainder is a design
that computes the capitalized total *independently* of the daily
figures and then reconciles the two — which is exactly the anti-pattern
that produces a remainder to discard in the first place, and which
silently loses money from the ledger, an unacceptable outcome for an
append-only financial system.

This build avoids the problem at the design level rather than handling
it after the fact: the capitalized total is *defined* as the running
sum of the already-rounded daily accruals (see NUMBERS.md §6). There is
no second, independently-computed "true" total for the sum to
disagree with — so no remainder can ever exist to be discarded. The
criterion describes a failure mode of a design this build doesn't use.

## Criteria reviewed and accepted

For completeness — these four were checked with the same rigor as the
four above and found correct, not simply left unexamined:

- **"Day 2 closing balance, evaluated at end of Day 5, before any fee,
  is AED −370.00."** Verified directly:
  `calculateBalance(acc001, Day2)` called after Day 5's events are
  admitted but before Day 5's fee sweep runs returns exactly `−370.00`
  (`1200 − 950 − 620`). See `EventReplayTest
  .day2ClosingBalance_atEndOfDay5_beforeFee_isMinus370`.
- **"The Day 4 settlement of Auth-A must be accepted."** Correct — a
  settlement amount (185.00) lower than the original hold (200.00) is
  ordinary partial settlement, not a violation of anything. Accepted
  and posted.
- **"Any settlement referencing a missing authorization ID must be
  rejected, funds must not leave the account."** Correct, and directly
  exercised by E6 (references `Auth-Z`, which was never authorized) —
  rejected with an error, no ledger entry created.
- **"If Auth-B is approved, its hold reduces available balance but not
  ledger balance."** Correct as a general statement of how holds work
  mechanically — true regardless of whether Auth-B specifically is
  approved. (It isn't, in this run — see AMBIGUITIES.md for the
  derivation of why Auth-B is actually rejected, which this criterion
  doesn't assert either way.)

## Approaches abandoned mid-build

- **Gating event admission by `value_date` instead of the event's own
  recorded `day`.** The first working version of the runner admitted
  an event once `!e.getValueDate().isAfter(currentDay)` — which meant
  E7 (`value_date` Day 2) and E9 (also `value_date` Day 2, reversing
  E7) both became eligible during the **Day 2** iteration, three and
  four processing-days early respectively, and cancelled each other out
  before the scenario's negative-balance window could ever be observed.
  Abandoned once traced to its root cause; replaced with gating on
  `event.getDay() == day`, with `value_date` reserved exclusively for
  filtering which already-applied entries count toward a given day's
  *closing balance* — a different question from *when an event is
  admitted*.

- **`double` for all monetary fields.** The original model classes
  (`Account`, `Event`, `LedgerEntry`, `Authorization`) all used `double`
  for amounts, with a hand-rolled `Math.round(value * 10^precision) /
  10^precision` rounding helper. Abandoned in favor of `BigDecimal`
  throughout, constructed from `String` literals rather than
  `valueOf(double)` — see NUMBERS.md §2 for why the `String` constructor
  specifically matters, not just the type change.

- **Retroactive, cascading fee/interest re-evaluation (Option B).**
  Fully scoped before being abandoned: every day from 1 through the
  current day re-checked on each day's close, with an idempotency guard
  per (account, day) so no day is fee-assessed twice, and interest
  moved from incremental per-day accrual to a single final pass at Day
  6 over fully-reconciled closing balances (incremental accrual can't
  survive a day's balance being revised *after* that day's interest was
  already accrued). Verified numerically against the same event stream:
  3 fees instead of 1 (Days 2, 4, 5), final pre-interest balance 390.00
  instead of 440.00, capitalized interest 0.93 instead of 0.83.
  Abandoned in favor of Option A on two grounds: the spec's wording
  supports Option A's reading at least as well as Option B's (see
  AMBIGUITIES.md), and this is fundamentally a business-logic decision,
  not an implementation detail — the two readings produce different fee
  counts, different fee dates, and different final account balances for
  the same event stream (see AMBIGUITIES.md §1 for the full comparison).
  Absent an explicit product rule settling which is intended, defaulting
  to the narrower, less retroactive interpretation (A) avoids silently
  imposing an assumption with real financial consequences that the spec
  itself doesn't state.

- **Un-scoped, cumulative error list, reprinted in full every day.**
  The first working version of `LedgerService.errors` was a single
  flat list with no per-day boundary, and the runner printed the whole
  list every day — meaning Day 4's `Auth-Z` settlement error reappeared
  verbatim in the Day 5 and Day 6 output, looking like three separate
  errors instead of one. Abandoned in favor of scoping each day's
  printed errors to only what was added since the previous day's print.

- **Mutable `Authorization.status`, considered for replacement with an
  immutable status-history model.** `Authorization` objects are
  mutated in place (`setStatus(...)`) rather than represented as a
  sequence of immutable status-change events. An alternative — treating
  every status transition as its own append-only record, so historical
  per-day authorization state could be reconstructed by replay rather
  than read off a single current-state object — was considered, since
  it would generalize correctly to a backdated authorization or
  settlement event (which this build's model doesn't handle correctly;
  see AMBIGUITIES.md). Abandoned for this submission on time grounds
  only, not because the concern is invalid: no event in this specific
  stream backdates an authorization or settlement, so the mutable model
  produces correct output here, but the limitation is real and
  documented rather than hidden.

- **`assertEquals(BigDecimal, BigDecimal)` using default `.equals()`
  semantics.** An early version of the Auth-B test asserted
  `assertEquals(new BigDecimal("0.00"), activeHoldTotal(...))` and
  failed with `expected: <0.00> but was: <0>` — `BigDecimal.equals()`
  is scale-sensitive, so `0.00` and unscaled `0` are numerically equal
  but not `.equals()`-equal, and JUnit's `assertEquals` on two
  `BigDecimal`s uses `.equals()`. Caught by actually running the suite,
  not by inspection. Replaced with an explicit
  `compareTo(...) == 0` comparison, which is scale-independent.

- **Test fixture that admitted all of Day 5's events before closing
  Days 1–4.** An early version of the Day-2-closing-balance test called
  `admitThroughDay(5)` (admitting E7 immediately) and only then
  `closeDaysUpTo(4)`. Because E7 was already in the ledger by the time
  Day 2's close ran, the fixture accidentally exercised Option B's
  behavior for one test — Day 2 got fee-assessed retroactively inside
  the test, producing `−395.00` (`−370.00` minus the wrongly-early fee)
  instead of the expected `−370.00`. Caught by an actual failing test
  run, not anticipated in advance. Fixed by interleaving admit-then-close
  strictly one day at a time through Day 4, matching the real runner's
  execution order, before separately admitting (but not closing) Day 5.