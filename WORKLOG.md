# WORKLOG.md

Real, timestamped log of work on this assignment, built directly from
`git log --pretty=format:"%h %ad %s" --date=iso` - ten commits,
2026-09-01 15:52 through 2026-09-02 07:19. Every entry below
corresponds to an actual commit; nothing is interpolated between them.

---

**Known limitation:** commit granularity does not
reflect development granularity, especially early on. `c1859ae`
("Initial commit") bundles the majority of the core engine including
finding and fixing the value_date-vs-day event admission bug, the full
double-to-BigDecimal migration across all model classes, and the
settlement/authorization/reversal guards into a single commit. That
work happened iteratively, in that rough order, but no intermediate
commit boundary exists to prove it, so no intermediate timestamp is
claimed here. Commit granularity improves from `11ffe50` onward, where
individual commits map to individual, describable pieces of work.
Where a commit message is itself thin or ambiguous (`11ffe50`, `8a924d7`),
that's noted rather than filled in with invented specificity.

---

**2026-09-01 15:52:19 (`c1859ae`)** — Initial commit. Core engine:
account/event/ledger-entry/authorization model, `LedgerService` event
processing (credit, debit, authorization, settlement, reversal),
`BigDecimal` throughout, overdraft fee logic, and the event-admission
fix (gating by event's own `day`, not `value_date`) that was the first
significant bug found and corrected before this commit was made. No
intermediate commit exists for the debugging process that preceded it.

**2026-09-01 16:06:23 (`8a924d7`)** — Added apply daily interest and
capitalize interest.

**2026-09-01 16:11:15 (`acaccb8`)** — Remove .idea folder from repo and
update .gitignore. Housekeeping, not logic.

*(Gap: no commits between 2026-09-01 16:11 and 2026-09-02 06:17 - slept,
then had office work the next morning before resuming with `11ffe50`.)*

**2026-09-02 06:17:11 (`11ffe50`)** — Tests added among several other
changes. Commit message is honest about being a bundle - covers
`EventReplayTest` authoring, and, per that same testing pass, two
test-side bugs found and fixed via an actual `mvn test` run: a
`BigDecimal` scale-mismatch assertion failure (`0.00` vs. unscaled `0`
not being `.equals()`-equal), and a test fixture that admitted Day 5's
events before closing Days 1-4, which accidentally exercised
cascading-fee behavior for one assertion and produced -395.00 instead
of the expected -370.00.

**2026-09-02 06:30:12 (`035850d`)** — Updated pom to add junit jupiter
engine. Found during a POM review that `junit-jupiter-api` was
declared without `junit-jupiter-engine` - the local `mvn test` run had
only worked because a compatible engine was already cached in `.m2`.
Pinned the Surefire plugin version in the same change and reran with
`mvn clean test` to confirm the fix wasn't masked by stale local state.

**2026-09-02 06:46:33 (`eacf151`)** — Added Readme.

**2026-09-02 06:48:56 (`8847276`)** — Added Numbers.md.

**2026-09-02 06:53:34 (`cb11b17`)** — Added Rejected.md.

**2026-09-02 07:14:14 (`9710c59`)** — Added Ambiguites.md. (Twenty-minute
gap from the prior commit - AMBIGUITIES.md and REJECTED.md's Option-A
reasoning were cross-checked against each other for consistency in
this window; an earlier draft of that reasoning had partly justified
the design choice by ease of live defense rather than the underlying
business-logic trade-off, and was rewritten before this commit.)

**2026-09-02 07:19:18 (`c109fa6`)** — Day 6 added in failure test case.
Fix to the intentional failing test: it previously stopped at Day 5, so
E9 (a Day 6 event) never actually posted, meaning the test's -180.00
result demonstrated "E7 hasn't been reversed yet" rather than the
claim it's meant to test - that reversing E7 doesn't fully undo its
consequences. Fixed by admitting (not closing) Day 6 before the
assertion; corrected result is 440.00.

---