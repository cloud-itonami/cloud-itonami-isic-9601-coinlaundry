# ADR-0001: A coin-laundry actor that deliberately breaks the cluster's shape

**Status**: accepted
**Date**: 2026-08-08
**Superproject record**: `com-junkawasaki/root` ADR-2800004000 §3

## Context

ISIC 9601's `includes` names "coin-operated laundry activities"
(measured from `cloud-itonami/org-un-isic`, 2026-08-08). The parent actor
implements an attended dry cleaner and has no unattended machine, no cash
handling and no abandoned-property op. The gap was found while
scaffolding the three missing cleaning industries and recorded in
ADR-2800004000 §3 as a fourth hole; the owner approved building it.

By the time this repo was written, the cluster's bailment shape had
repeated five times without modification (9601, 9522, 9523, 9601-carpet,
4520-carwash). **The main risk to this repo was therefore not omission —
it was copying.**

## Decision

### 1. No return op, because nothing is handed over

In a self-service laundry the customer operates the machine. The operator
never takes possession of their laundry, so a `return` op would model a
custody relationship that does not exist. The vocabulary has none, and
`phase_test.clj/this-is-not-the-bailment-shape` asserts the absence by
scanning `allowed-ops` for anything matching `return` — so a future edit
that "restores consistency with the siblings" fails the suite.

The store keys on the **machine** rather than on a ticket, for the same
reason.

### 2. The checks are temporal, because the risks are

The siblings' strongest checks are material incompatibility (fibre,
finish, animal condition) and arithmetic identity (parts cost, water
reclaim). Those need a held object. An unattended site holds nothing, so
its risks are elapsed time and current state:

- **sanitation inspection overdue** — the site's own last-inspection day
  against the jurisdiction's own interval. **An unknown interval returns
  `true` (overdue)**, deliberately: an interval that cannot be computed
  is not the same as one that has not elapsed, and "no data" must never
  read as "no obligation."
- **abandonment period not elapsed** — the day property was left against
  the site's **own posted** retention period. Missing data holds.
- **machine cycle running** — someone's clothes are inside it.

The actor never reads a clock; day-stamps are data. That is what makes
the demo byte-reproducible, and it is also why the checks are testable
without freezing time.

### 3. Hold is in the vocabulary; dispose is not

Taking abandoned laundry into custody is the operator's furthest legitimate
reach. Disposing of it, selling it or transferring ownership is
lost-property law and often involves the police. Those are **absent**,
not gated — an actor that could hold and also dispose would be one
confident proposal away from throwing away a stranger's clothes.

### 4. `high-stakes` on op names; request key `:subject`

Following 9601 (this repo's parent) rather than 9522/9523, and avoiding
the fleet's subject-key debt (ADR-2800004000).

## Consequences

### What this buys

- The coin-operated half of 9601 has an implementation, and the claim is
  checkable: 5 commits and 6 distinct governor holds.
- The cluster now has a **counter-example to its own dominant shape**,
  with the difference asserted in a test rather than described in prose.

### What it costs, stated rather than hidden

- **A substring scope gate cannot tell a claim from a denial.** Measured
  here: the advisor's first rationale honestly said the actor cannot
  処分/売却 the property, and that sentence tripped the gate on every
  legitimate run. The fix was moving the boundary statement out of
  scanned text — **not** teaching the scanner about negation, which is
  the kind of cleverness a gate must not depend on. The same hazard
  applies to every sibling with a prose scan; this is the first time it
  actually fired.
- **No cash handling.** Collection, float and payment reconciliation are
  out of scope. A real operator needs them; this actor does not provide
  them.
- **The sanitation instrument is a guideline, not a statute.** JPN's
  コインオペレーションクリーニング営業施設 requirements come from a
  厚生労働省 通知 rather than from クリーニング業法's 届出 regime, and the
  facts table says so in `:statutory-context` rather than overclaiming a
  statutory duty.
- **`DatomicStore` does not exist here.** Only `MemStore`.
- **Not on the shared surface.** `os.edn` declaration is a separate step.
