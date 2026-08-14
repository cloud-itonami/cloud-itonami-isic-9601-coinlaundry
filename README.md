# cloud-itonami-isic-9601-coinlaundry

Open Business Blueprint for a **coin-operated (self-service) laundry** —
a role-suffix satellite of
[`cloud-itonami-isic-9601`](https://github.com/cloud-itonami/cloud-itonami-isic-9601)
(ISIC 9601: washing and (dry-)cleaning of textile and fur products).

## Why this repo exists, measured rather than assumed

**ISIC 9601's `includes` list names "coin-operated laundry activities"** —
read from this workspace's spec mirror (`cloud-itonami/org-un-isic`,
`data/classes/9601.json`) on 2026-08-08.

The parent actor implements an **attended** dry cleaner: `:garment/intake`
→ `:careplan/verify` → `:certification/screen` →
`:actuation/apply-cleaning-process` → `:actuation/return-garment`. There
is no unattended machine, no coin/cash handling and no abandoned-property
op anywhere in its schema. So the coin-operated half of 9601 had no
implementation.

## This is deliberately NOT the bailment shape

Five actors in this cluster share one shape — the customer hands
something over, the operator acts on it, the operator hands it back:
9601 (garments), 9522 (appliances), 9523 (footwear), 9601-carpet (rugs),
4520-carwash (vehicles).

**A coin laundry is not that.** The customer operates the machine
themselves; the operator never takes possession of their laundry. So:

- **there is no `:actuation/return-*` op in this actor at all** — not
  gated, *absent*, because there is nothing to return
- the store keys on the **machine**, not on a ticket for a customer's item
- `phase_test.clj` asserts the absence directly, so a future edit that
  "restores consistency with the siblings" fails

Copying the sibling shape here would have invented a custody
relationship that does not exist.

## The two things that ARE irreversible

1. **Suspending a machine** — a customer's clothes may be inside it,
   mid-cycle. Cutting a running machine is not an administrative act.
2. **Taking abandoned property into custody** — the *one* moment an
   unattended operator holds someone else's property, governed by
   lost-property law rather than by a service contract.

Neither auto-commits at any phase.

## What is absent from the vocabulary, not merely gated

| Not here | Why |
|---|---|
| disposing of abandoned property | lost-property law; often requires the police |
| selling it / transferring ownership | same |
| settling a damage claim | the operator, their insurer and the customer |

Taking laundry into custody is already the operator's furthest reach. An
actor that could *hold* and also *dispose* would be one confident
proposal away from throwing away a stranger's clothes.

## Why every check here is about elapsed time

The bailment siblings hold a physical thing, so their strongest checks
are **material incompatibility** (fibre, finish, animal condition) and
**arithmetic identity** (parts cost, water reclaim). An unattended site
holds nothing, so its risks are **temporal**:

| Check | Recomputed from |
|---|---|
| sanitation inspection overdue | the site's own last-inspection day vs the jurisdiction's own interval. **An unknown interval reads as overdue**, never as "no obligation" |
| abandonment period not elapsed | the day property was left vs the site's **own posted** retention period. Missing data holds |
| machine cycle running | the machine's own recorded state |

The actor **never reads a clock** — day-stamps are data, which is what
makes the demo and the tests byte-reproducible.

## What the regulation actually is

ISIC calls it laundry, but a coin laundry is not a クリーニング所: nothing
is received, processed and returned, so the 届出 regime binding an
attended cleaner does not bind it the same way. What binds it in JPN is
the 厚生労働省 guideline for **コインオペレーションクリーニング営業施設** —
periodic cleaning and disinfection, and a posted notice to users. That is
a *sanitation* regime for an unattended room, which is why the required
evidence is inspection records and a posted notice rather than a customer
consent form.

## Run it

```bash
clojure -M:dev:run          # 5 commits and 6 distinct governor holds
clojure -M:dev:test         # 24 tests / 66 assertions
clojure -M:dev:render-html  # regenerate docs/samples/operator-console.html
```

`:render-html` drives the same real actor graph over the same real seed
and writes the operator console from what comes back — machine ids,
suspension and custody numbers, hold rules and their detail strings are
all read out of the store and the governor, never typed. Its scenario is
longer than `:run`'s: it commits the sanitation plan a jurisdiction
requires *before* attempting each actuation, so every one of the
governor's nine HARD rules fires on a row of its own instead of
`:evidence-incomplete` co-firing with two of them. **It throws rather
than write a console carrying no `:governor-hold` fact** — a page that
shows no real hold is not evidence of a governor.

```
:committed     :machine/register                  machine-1
:committed     :sanitation-plan/verify            machine-1
:committed     :inspection/screen                 machine-1
:committed     :actuation/suspend-machine         machine-1
:committed     :actuation/hold-abandoned-property machine-1
:governor-hold :sanitation-plan/verify            machine-2 [:no-spec-basis]
:governor-hold :inspection/screen                 machine-3 [:sanitation-inspection-overdue]
:governor-hold :actuation/suspend-machine         machine-4 [:evidence-incomplete :machine-cycle-running]
:governor-hold :actuation/hold-abandoned-property machine-5 [:evidence-incomplete :abandonment-period-not-elapsed]
:governor-hold :actuation/dispose-abandoned-property machine-1 [:op-not-allowed :scope-excluded]
:governor-hold :actuation/suspend-machine         machine-1 [:already-suspended]
```

## A finding worth repeating

**A substring scope gate cannot tell a claim from a denial.** The first
draft of the advisor's `:actuation/hold-abandoned-property` rationale
honestly said "this actor cannot 処分 or 売却 it" — and that sentence
contains the very terms the gate scans for, so *every* legitimate
hold-property run was held with `:scope-excluded`. The demo caught it.

The fix is on the advisor's side (state the boundary in the ns docstring
and this README, where no scanner reads it), **not** a smarter scanner —
negation detection is exactly the kind of cleverness a gate must not
depend on. Recorded in `coinlaundry.governor`'s docstring.

## Honest state

- **`DatomicStore` is not implemented.** Only `MemStore`.
- **No cash/coin handling.** Collection, float management and payment
  reconciliation are absent; this actor coordinates sanitation, machine
  state and abandoned property.
- **Not connected to the 営み OS yet.** Standard-form, so the adapter
  will be a three-line shim, but the `os.edn` declaration is separate.
- `high-stakes` is a set of **op names**; the request key is `:subject`.

## License

AGPL-3.0-or-later. See `LICENSE`.
