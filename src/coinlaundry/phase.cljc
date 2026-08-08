(ns coinlaundry.phase
  "Phase 0->3 staged rollout for an **unattended, self-service** coin
  laundry.

    Phase 0  read-only         -- no writes, still governor-gated.
    Phase 1  assisted-intake   -- machine registration allowed, every
                                  write needs human approval.
    Phase 2  assisted-verify   -- adds sanitation-plan verification +
                                  inspection screening writes, still
                                  approval.
    Phase 3  supervised auto   -- governor-clean, high-confidence
                                  `:machine/register` may auto-commit.
                                  `:actuation/suspend-machine` and
                                  `:actuation/hold-abandoned-property`
                                  NEVER auto-commit, at any phase.

  ## This is NOT the bailment shape, and that is the point

  Four sibling actors in this cluster (9601 garments, 9522 appliances,
  9523 footwear, 9601-carpet rugs) and 4520-carwash all share one shape:
  the customer hands something over, the operator does something to it,
  the operator hands it back. **A coin laundry is not that.** The
  customer operates the machine themselves; the operator never takes
  possession of their laundry.

  So **there is no `:actuation/return-*` op in this actor at all** --
  not gated, absent, because there is nothing to return. Copying the
  bailment shape here would have invented a custody relationship that
  does not exist. `phase_test.clj` asserts the absence directly.

  ## The two things that ARE irreversible

  1. **Suspending a machine.** A customer's clothes may be inside it,
     mid-cycle. Cutting a running machine is not an administrative act.
  2. **Taking abandoned property into custody.** This is the *one*
     moment the operator holds someone else's property, and it is
     governed by lost-property law rather than by a service contract.

  Both are absent from every phase's `:auto` set -- **a permanent
  structural fact, not a rollout milestone still to come**.
  `coinlaundry.governor`'s high-stakes set asserts the same invariant
  independently.

  `:inspection/screen` is likewise never auto-eligible, matching every
  sibling's screening op."
  (:require [clojure.set :as set]))

(def read-ops #{})

(def write-ops #{:machine/register :sanitation-plan/verify :inspection/screen
                 :actuation/suspend-machine :actuation/hold-abandoned-property})

;; NOTE the invariant: the two `:actuation/*` ops are members of
;; `write-ops` (governor-gated like any write) but are NEVER members of
;; any phase's `:auto` set below. Do not add them there.
(def phases
  {0 {:label "read-only"       :writes #{}                     :auto #{}}
   1 {:label "assisted-intake" :writes #{:machine/register}    :auto #{}}
   2 {:label "assisted-verify"
      :writes #{:machine/register :sanitation-plan/verify :inspection/screen}
      :auto   #{}}
   3 {:label "supervised-auto" :writes write-ops
      :auto #{:machine/register}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))

(defn auto-eligible-ops
  "Every op that any phase may auto-commit."
  []
  (reduce set/union #{} (map :auto (vals phases))))
