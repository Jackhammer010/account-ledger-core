# NUMBERS.md

Every constant, threshold, and representational choice in this codebase,
with the reasoning behind the specific value chosen — and, where the
assignment invites it, why not some other plausible value (e.g. half
it). Every derived number below was independently verified against a
parallel Python/Decimal reimplementation of the same algorithm before
being written down here, not hand-calculated once and trusted.

---

## 1. Rounding mode: `RoundingMode.HALF_UP`, applied once, at the end

**Value:** `HALF_UP`, used in exactly two places — `calculateBalance`'s
final `setScale`, and the interest multiplication in
`applyDailyInterest`.

**Why HALF_UP and not HALF_EVEN (banker's rounding):** `HALF_EVEN` is
the more "statistically fair" choice over a large number of roundings
(it doesn't systematically bias totals upward), and it's Java's own
default for `BigDecimal.equals`-adjacent contexts in some libraries.
It's not chosen here because the spec doesn't ask for statistical
fairness over volume — it asks for two specific properties: exact
figures a human can verify, and a lot of terminating `x.5` values in a
"0.04% of a two/three-decimal number" domain that HALF_EVEN would round
inconsistently depending on whether the preceding digit happens to be
even. HALF_UP is deterministic and matches how currency amounts are
conventionally rounded in retail/consumer contexts (as opposed to
scientific/statistical ones) — which fits a ledger a customer might
dispute a fee against more than HALF_EVEN would.

**Why applied once, at the end, and not per-entry:** `calculateBalance`
sums all applicable entries as full-precision `BigDecimal` first, and
only calls `setScale` on the final sum — it does not round each
individual `LedgerEntry.amount` on the way in. Rounding per-entry would
let rounding error compound across N entries; rounding once, on the
final sum, bounds the total rounding error to at most half a unit at
the account's precision, regardless of how many entries contributed.
This is the same reasoning that makes interest accrual sum-then-round
rather than round-then-sum an important choice — see §4.

---

## 2. Monetary literals constructed from `String`, never `double`

**Value:** every `BigDecimal` monetary constant in the codebase (fee
amount, interest rate, opening balances, event amounts) is built via
`new BigDecimal("...")`, never `BigDecimal.valueOf(double)` or a bare
`double` literal.

**Why:** `BigDecimal.valueOf(0.0004)` round-trips through IEEE-754
binary floating point before becoming a `BigDecimal` — `0.0004` has no
exact binary representation, so the "precise" decimal type would
silently inherit binary rounding error at the exact point it exists to
avoid. Constructing from the literal string (`new
BigDecimal("0.0004")`) parses the decimal digits directly, with no
binary intermediate. This is why the entire codebase (models, event
stream, service logic) migrated away from `double` for money early —
see WORKLOG.md for that specific pass.

---

## 3. Currency precision: AED = 2 decimal places, BHD = 3

**Value:** given directly by the spec, not derived — `Account.precision`
is `2` for ACC-001, `3` for ACC-002.

**Why not the same precision for both:** this isn't an arbitrary
design choice to defend — AED and BHD genuinely have different minor
units in real-world currency (AED: fils, 2dp conventionally displayed;
BHD: fils, 3dp, one of a small number of real-world currencies with
3-decimal precision). The spec states this explicitly as non-negotiable,
and the codebase reads `precision` per-`Account` rather than hardcoding
2 anywhere, specifically so this isn't accidentally assumed uniform.
The BHD instalment-split logic (§6) is the part of the codebase most
exposed to this — if it assumed 2dp, it would silently corrupt
3-decimal amounts.

---

## 4. Overdraft fee: AED 25.00 flat, not proportional

**Value:** `new BigDecimal("-25.00")`, applied as a single flat debit,
independent of how negative the balance is or how long it's been
negative.

**Why 25.00 and not, say, 12.50 (half):** given directly by the spec as
non-negotiable — not derived from a formula. The number itself isn't
mine to justify; what's mine to justify is *how* it's applied. Two
design choices bundled into this constant:

- **Flat, not scaled to the overdraft amount.** A balance of −0.01 and
  a balance of −10,000.00 both incur exactly one 25.00 fee under this
  design. Nothing in the spec suggests a tiered or proportional fee, so
  flat-and-fixed was the simpler, more literal reading — proportional
  fee logic would be an invented requirement, not an implemented one.
- **Once per (account, day), not once per negative event.** A single
  day can contain several ledger-affecting entries; only the day's
  final closing balance is checked, once, per the spec's explicit "once
  per day per account" cap — see AMBIGUITIES.md for how this cap
  informed the choice between evaluating a day's balance once
  (Option A, adopted) versus re-evaluating it on every later day a
  backdated entry could affect it (Option B, rejected for this build).

---

## 5. Daily interest rate: 0.04% → multiplier `0.0004`

**Value:** `new BigDecimal("0.0004")`, multiplied directly against that
day's closing balance.

**Why 0.0004 and not, say, 0.0002 (half) or 0.004 (10x):** given
directly by the spec ("0.04% per day") — `0.04 / 100 = 0.0004` is
arithmetic, not a design decision. What *is* a design decision: the
rate is applied to the account's **closing ledger balance for that
specific day**, not to an average daily balance, not to the balance at
a single fixed time of day, and not compounded day-over-day (each
day's interest is `balance × 0.0004`, not `previous_balance ×
(1.0004)^n`). Simple, non-compounding daily accrual is the literal
reading of "0.04% per day on the closing ledger balance."

---

## 6. Interest capitalization: sum of rounded dailies, not an independently reconciled total

**Value:** the Day 6 capitalized interest credit is defined, in code,
as the running sum of each day's *already-rounded* daily accrual — not
computed as one unrounded total-over-six-days and then reconciled
against the sum of the dailies.

**Why this order matters:** the spec requires "the rounded daily
accruals must sum exactly to the capitalized total." If the capitalized
total were instead computed independently (e.g. `sum_of_balances ×
0.0004`, rounded once at the end) and then compared against the sum of
six separately-rounded daily figures, the two would generically
*disagree* by up to a few hundredths — six independent roundings don't
reliably sum to the same value as one rounding of the total. By
defining the capitalized total as *literally* the accumulator that the
six daily roundings were added into, the "must sum exactly" requirement
is satisfied by construction — there is no second, independently
computed number to disagree with. This is also the direct basis for
rejecting acceptance criterion 8 ("if the rounded accruals don't sum to
the capitalized total, the remainder is discarded") — see REJECTED.md.

**Verified breakdown, ACC-001 (AED), under this build's Option A design:**

| Day | Closing balance used | `balance × 0.0004` | Rounded (2dp) |
|---|---|---|---|
| 1 | 250.00 | 0.100 | 0.10 |
| 2 | 250.00 | 0.100 | 0.10 |
| 3 | 650.00 | 0.260 | 0.26 |
| 4 | 465.00 | 0.186 | 0.19 |
| 5 | −180.00 | — (negative, no accrual) | — |
| 6 | 440.00 | 0.176 | 0.18 |
| **Capitalized total** | | | **0.83** |

**Verified breakdown, ACC-002 (BHD):**

| Day | Closing balance used | `balance × 0.0004` | Rounded (3dp) |
|---|---|---|---|
| 1–4 | 0.000 | — (zero, not positive) | — |
| 5 | 10.000 | 0.004 | 0.004 |
| 6 | 10.000 | 0.004 | 0.004 |
| **Capitalized total** | | | **0.008** |

---

## 7. Zero balance: neither negative nor positive — no fee, no interest

**Value:** the fee check uses `compareTo(ZERO) < 0` (strictly negative);
the interest check uses `compareTo(ZERO) > 0` (strictly positive).
A balance of exactly `0.00`/`0.000` triggers neither.

**Why not treat zero as eligible for one or the other:** the spec's own
language — "positive balances only" for interest, "is negative" for the
fee — draws the line at zero on both sides. ACC-002 sits at exactly
`0.000` for Days 1–4 in this event stream, so this boundary is directly
exercised, not just a hypothetical edge case: no interest accrues on
those four days.

---

## 8. Available-balance / hold approval threshold: `>= 0`, inclusive

**Value:** an authorization is approved when
`(ledgerBalance − activeHolds − newHoldAmount).compareTo(ZERO) >= 0` —
a resulting available balance of exactly zero is accepted, not
rejected.

**Why inclusive:** the spec states the boundary explicitly — "remains
at or above zero." "At... zero" is unambiguous; there was no real
alternative reading here, but it's stated because it's the exact
comparison operator (`>=`, not `>`) that determines Auth-B's fate if
the arithmetic ever landed exactly on zero rather than the −245.00 it
actually lands on in this event stream (see AMBIGUITIES.md for the full
Auth-B derivation).

---

## 9. BHD instalment split: base-then-remainder, remainder to the *last* instalment(s)

**Value:** `splitEqual(total, parts, scale)` computes
`base = floor(total / parts)` at the target scale (`RoundingMode.DOWN`),
then distributes whatever's left (always less than one unit at that
scale) one unit at a time, starting from the **last** instalment and
working backward.

For E10 (BHD 10.000 split 3 ways): `base = 3.333` (`10.000/3 =
3.3333...`, truncated to 3dp), `3.333 × 3 = 9.999`, remainder =
`0.001` = exactly one unit at 3dp. Result: **[3.333, 3.333, 3.334]**.

**Why "last instalment gets the remainder" and not "first," and not
split-the-difference:** this is a genuine arbitrary convention — the
spec doesn't say which instalment absorbs the odd unit, and there's no
principled reason "last" beats "first." It's documented here precisely
*because* it's arbitrary: the property that actually matters (the three
values sum exactly to 10.000, at 3dp, with no fractional unit lost) is
guaranteed by the algorithm regardless of which instalment gets picked.
"Split the difference" (e.g. two instalments absorbing half a unit each)
isn't possible here — a unit at 3dp (`0.001`) isn't itself divisible at
that same precision, so *some* instalment has to take the whole
remaining unit. This is also the direct, constructive proof that
acceptance criterion 7 ("each instalment must be BHD 3.334") is
arithmetically impossible: `3.334 × 3 = 10.002 ≠ 10.000` — three equal
instalments literally cannot sum to a total that isn't itself evenly
divisible by 3 at that precision. See REJECTED.md.

---

## 10. Calendar anchor: Day 1–6 mapped to 2026-09-01 through 2026-09-06

**Value:** `LocalDate.of(2026, 9, 1)` through `..., 9, 6)`, used purely
so `Event`/`LedgerEntry` can carry a `LocalDate` rather than a bare
integer.

**Why this specific week, and why it doesn't matter:** the actual dates
are arbitrary — nothing in the fee, interest, or balance logic reads
month/year, only relative ordering (`isAfter`, `equals`, `compareTo`)
between dates that are always exactly 1–6 days apart. Any 6 consecutive
calendar dates would produce identical results. The specific week
chosen has no significance and isn't worth defending beyond "it's
consecutive and doesn't cross a February 29th or other edge case that
isn't relevant here anyway."