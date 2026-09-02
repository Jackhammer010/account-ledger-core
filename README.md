# Account Ledger Core

An in-memory, event-sourced account ledger for two accounts (ACC-001, AED;
ACC-002, BHD) over a fixed 6-day window. No web layer, no database, no UI —
this is a Maven project exercised entirely by a replay script and a JUnit
test suite.

## Requirements

- Java 21
- Maven (uses the `mvn` wrapper/install on your PATH)

Verify with `java -version` and `mvn -version` before running anything —
the numbers in NUMBERS.md assume Java 21's
`BigDecimal` behavior specifically.

## Running the test suite

`mvn clean test`


(`clean` first is deliberate — it forces a full rebuild against the
declared dependencies rather than whatever happens to already be
compiled, which matters for reproducibility on a machine other than
mine.)

This compiles the project and runs `EventReplayTest`, which drives the
same event stream as the replay script but asserts specific values at
specific points (e.g. "Day 2's closing balance, evaluated at end of Day
5, is exactly −370.00") rather than just printing output for a human to
read.

**Expect this command to exit with `BUILD FAILURE`.** That's correct,
not a bug: one test in the suite —
`FAILING_afterE9_balanceDoesNotFullyReturnToPreE7Value` — is an
intentional failing test, required by the assignment. It encodes
acceptance criterion 6 ("after E9, all balances and fees return to
their pre-E7 values") literally and asserts it; the assertion fails
because that criterion is false under this design (see REJECTED.md for
why). The other 8 tests pass. Do not "fix" the build to green by
disabling that test or ignoring failures in the Surefire config — a
green build here would mean the deliverable is missing, not that the
code improved.

You should see `Tests run: 9, Failures: 1, Errors: 0` in the output. If
you see `Tests run: 0` instead, the JUnit engine didn't resolve — check
that both `junit-jupiter-api` and `junit-jupiter-engine` are present in
`pom.xml`.

To run everything *except* the intentional failure, to sanity-check the
rest of the suite in isolation:

`mvn test -Dtest=EventReplayTest#day2ClosingBalance_atEndOfDay5_beforeFee_isMinus370`


(swap in any other test method name from `EventReplayTest.java`; each
one can be run individually the same way).

## Running the replay script

The test suite is the authoritative, assertion-backed source of truth.
The replay script (`EventReplayRunner.main`) exists separately because
the assignment specifically asks for **printed**, human-readable, per-day
output — the two are not redundant.

Compile once, then run the main class directly against the compiled
classes (no `exec` plugin is configured in this project):

mvn compile
java -cp target/classes com.example.ledger.EventReplayRunner


## Reading the output

The script prints one block per day, Day 1 through Day 6. For each
account, in order:
~~~
ACC-001 Ledger Balance: <closing ledger balance, all entries with value_date <= today>
ACC-001 Available Balance: <ledger balance minus active holds>
ACC-001 Fee Assessed: <amount> (value_date <date>) -- only printed on a day a fee posted
ACC-001 Interest Capitalized: <amount> (value_date <date>) -- only printed on Day 6
ACC-001 Authorizations:
<auth id> <ACTIVE|SETTLED|REJECTED> <hold amount>
~~~
followed by:
~~~
Errors: [<any errors raised while processing that day's events>]
~~~

A few things that are easy to misread if you're skimming:

- **"Ledger Balance" already reflects that day's fee, if one was
  assessed.** Fees are applied before the balance is printed, not after
  — e.g. Day 5 shows `-180.00`, which is the pre-fee balance of
  `-155.00` minus that day's `-25.00` fee, not the raw event-stream
  effect of E7 alone.
- **A fee's printed `value_date` is the day it was *assessed*, not
  necessarily the day whose balance triggered it.** E7 is backdated
  (recorded Day 5, `value_date` Day 2), but the fee it causes is dated
  Day 5, not Day 2 — this is a deliberate design choice (Option A, see
  AMBIGUITIES.md), not a display bug.
- **Rejected authorizations still appear in the Authorizations list**,
  with status `REJECTED`, rather than being silently omitted — Auth-B
  is the example in this event stream.
- **Errors are per-day only** — an error raised on Day 4 (e.g. E6's
  invalid settlement reference) is not re-printed on Day 5 or Day 6.

## Repository layout

src/main/java/com/example/ledger/
model/ Account, Event, LedgerEntry, Authorization
service/ LedgerService — event processing, balance/fee/interest logic
EventReplayRunner.java main() + the event stream + the day-close loop
src/test/java/com/example/ledger/
EventReplayTest.java assertion-backed tests, including the one intentional failure


`NUMBERS.md`, `AMBIGUITIES.md`, `REJECTED.md`, and `WORKLOG.md` sit at
the repository root alongside this file.