(ns coinlaundry.registry
  "Pure record drafting + the two ground-truth recomputations the Coin
  Laundry Governor relies on. Nothing here reads a proposal.

  Both recomputations are about **elapsed time**, which is what makes
  this actor different from its bailment siblings: their strong checks
  are material incompatibility (fibre, finish, animal condition) and
  arithmetic identity (parts cost, water reclaim). An unattended site's
  risks are 'has the required interval passed' -- for sanitation, and
  for the moment property may be treated as abandoned."
  (:require [clojure.string :as str]))

;; ----------------------------- elapsed-time identities -----------------------------

(defn days-since
  "Whole days between two integer day-stamps, or nil when either is
  missing. Day-stamps are supplied by the caller (the actor never reads
  a clock -- `sim` and the tests pass fixed values, which is what makes
  the demo byte-reproducible)."
  [from-day as-of-day]
  (when (and (number? from-day) (number? as-of-day))
    (- as-of-day from-day)))

(defn sanitation-inspection-overdue?
  "Has the site gone longer than its jurisdiction's interval without a
  recorded sanitation inspection?

  **Returns true when the interval is unknown**, because an interval
  that cannot be computed is not the same as an interval that has not
  elapsed. A missing jurisdiction must not read as 'no obligation'."
  [{:keys [last-inspection-day as-of-day]} interval-days]
  (cond
    (nil? interval-days) true
    (nil? last-inspection-day) true
    :else (let [d (days-since last-inspection-day as-of-day)]
            (boolean (and d (> d interval-days))))))

(defn abandonment-period-not-elapsed?
  "Has the site's posted retention period NOT yet passed for property
  left in a machine?

  The operator may not treat something as abandoned before the period it
  itself posted has run. Missing data holds: if either the day it was
  left or the posted period is unknown, the period has not been shown to
  have elapsed."
  [{:keys [left-at-day posted-retention-days as-of-day]}]
  (if (or (nil? left-at-day) (nil? posted-retention-days))
    true
    (let [d (days-since left-at-day as-of-day)]
      (or (nil? d) (< d posted-retention-days)))))

;; ----------------------------- record drafting -----------------------------

(defn- seq->number [prefix jurisdiction seq-n]
  (str prefix "-" (str/upper-case (or jurisdiction "XXX")) "-"
       (str/join (repeat (max 0 (- 4 (count (str (inc seq-n))))) "0"))
       (inc seq-n)))

(defn register-machine-suspension [machine-id jurisdiction seq-n]
  {"suspension_number" (seq->number "SUS" jurisdiction seq-n)
   "machine_id" machine-id "jurisdiction" jurisdiction})

(defn register-abandoned-property-hold [machine-id jurisdiction seq-n]
  {"custody_number" (seq->number "CUS" jurisdiction seq-n)
   "machine_id" machine-id "jurisdiction" jurisdiction})

(defn append [coll record] (conj (vec coll) record))
