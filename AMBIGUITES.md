# AMBIGUITIES.md

Every point below is somewhere the spec was genuinely underspecified —
not a bug, not a missed requirement, but a real fork where more than one
reading is defensible. For each: what's ambiguous, which reading this
build takes, and why. Several of these directly determine numbers in
NUMBERS.md or arguments in REJECTED.md; cross-referenced where relevant.

---

## 1. When is "that day's closing ledger balance" evaluated? (Option A vs. Option B)

**The ambiguity.** The fee rule defines closing balance structurally —
"all entries with value_date ≤ that day" — as a function over whatever
is currently in the ledger. It does not say whether that function is
evaluated once, at the day's own close, and then frozen forever
(**Option A**), or re-evaluated every time the ledger grows, so a later
backdated entry can retroactively flip an earlier day's fee/interest
outcome (**Option B**). The wording supports both: "assessed once per
day per account" reads naturally as a hard cap that would only need
stating if the balance *could* be checked more than once per day — which
only happens under Option B. Acceptance criterion 1 ("Day 2's balance,
evaluated at end of Day 5") also explicitly treats "closing balance" as
a (day, evaluation-time) pair with different answers depending on when
you ask — which is the entire premise Option B runs on.

**Resolution: Option A.** Each day is evaluated exactly once, at its
own close, using whatever the ledger contains at that point in the
event stream. A backdated entry can still change an *already-computed*
historical balance if queried later (criterion 1 depends on this), but
it does not retroactively trigger a fee or interest adjustment for a
day that already closed.

**Why A over B:** both are internally consistent designs — B was fully
scoped and numerically verified before being set aside (see REJECTED.md,
"Approaches abandoned"). The spec's wording doesn't clearly compel one
over the other. More importantly, this is fundamentally a business-logic
decision, not an implementation detail — the two readings produce
different fee counts, different fee dates, and different final account
balances for the same event stream (see the comparison table below).
Absent an explicit product rule settling which is intended, this build
defaults to the narrower, less retroactive interpretation (A), rather
than silently imposing an assumption with real financial consequences
that the spec itself doesn't state. If the intended business rule is
actually B, that's a design swap with the numbers already worked out
(see REJECTED.md), not a rebuild.

**Downstream effect:** this single decision determines the fee count
(1 under A, 3 under B), the fee's dated day (Day 5 under A, Day 2 under
B), the final Day 6 balance (440.00 vs. 390.00 pre-interest), and the
capitalized interest total (0.83 vs. 0.93). Every other number in this
repository assumes Option A.

---

## 2. "Booked with value_date equal to the day assessed" — assessed *when*, exactly?

**The ambiguity.** Once Option A is chosen (§1), a second, narrower
question remains: when a fee is triggered by a backdated entry (E7,
`value_date` Day 2, discovered on Day 5), is "the day assessed" the day
whose balance was found negative (Day 2 — the value_date the triggering
entry carries) or the day on which the negative condition was actually
*discovered and acted on* (Day 5 — where Option A's once-per-day check
actually runs)? Both are "a day" in a defensible sense.

**Resolution:** Day 5 — the discovery day. Under Option A, Day 2's
fee-eligibility was already checked and passed clean before E7 existed;
the *only* moment this build ever observes a negative balance because
of E7 is Day 5's own close. Dating the fee to Day 2 would require
reaching backward to a day that has already closed, which is exactly
the mechanism Option A was chosen to avoid. This is why the fee's
printed `value_date` reads Day 5, not Day 2 — confirmed directly by
`EventReplayTest`'s assertion on `fees.get(0).getValueDate()`.

---

## 3. Interest and fee ordering within a single day's close

**The ambiguity.** On a day where both an overdraft fee and interest
could apply, does the fee post before interest is calculated (so
interest reflects the post-fee balance) or are both computed
simultaneously against the pre-fee balance?

**Resolution:** fee first, then interest, sequentially —
`closeDay()` calls `applyOverdraftFee` before `applyDailyInterest` for
each account. This has no visible effect in this specific event stream
(the one day a fee posts, Day 5, the balance is negative both before
and after the fee, so interest doesn't accrue either way — see
NUMBERS.md §7 on the zero/negative boundary), but the ordering is a
real, silent decision that would matter on a different event stream
where a fee brought a balance from slightly positive to negative, or
vice versa. Documented here because "why fee-then-interest and not the
reverse, or