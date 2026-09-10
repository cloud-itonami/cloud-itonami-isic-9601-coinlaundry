(ns coinlaundry.store
  "SSoT for the coin-laundry actor, behind a `Store` protocol so the
  backend is a swap, not a rewrite.

    - `MemStore` -- atom of EDN. The deterministic default.

  **`DatomicStore` is not implemented here yet.** The protocol and the
  contract test are here so adding one is a drop-in.

  ## The entity is a machine, not a customer's item

  Every sibling in this cluster keys its store on a ticket -- a record of
  something the customer handed over. **This actor has no such record**,
  because in a self-service laundry nothing is handed over. It keys on
  the **machine**, and the two actuations attach to it: suspending it,
  and logging property left inside it into custody.

  Two actuation events (machine suspension, abandoned-property hold) act
  on the SAME entity, each with its OWN history collection, sequence
  counter and dedicated guard boolean (`:suspended?` / `:property-held?`,
  **never a `:status` value**).

  The day-stamps (`:last-inspection-day`, `:left-at-day`, `:as-of-day`)
  are integers supplied as data. **The actor never reads a clock**, which
  is what makes the demo and the tests byte-reproducible."
  (:require [coinlaundry.registry :as registry]))

(defprotocol Store
  (machine [s id])
  (all-machines [s])
  (inspection-screening-of [s machine-id] "committed inspection screening verdict, or nil")
  (sanitation-plan-of [s machine-id] "committed sanitation-plan verification, or nil")
  (ledger [s])
  (suspension-history [s])
  (custody-history [s])
  (next-suspension-sequence [s jurisdiction])
  (next-custody-sequence [s jurisdiction])
  (machine-already-suspended? [s machine-id])
  (property-already-held? [s machine-id])
  (commit-record! [s record])
  (append-ledger! [s fact])
  (with-machines [s machines]))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A self-contained machine set. Each machine exists to make exactly one
  governor rule reachable. `:as-of-day` is day 100 throughout.

    machine-1  the clean path (inspected day 90, idle, property left
               day 80 with a 7-day posted period -- elapsed)
    machine-2  jurisdiction \"ATL\" -- no spec-basis on file
    machine-3  last inspected day 50 -- 50 days > the 30-day interval
    machine-4  a cycle is running right now
    machine-5  property left day 98, 7-day posted period -- not elapsed"
  []
  {:machines
   {"machine-1" {:id "machine-1" :site "Sakura-dori branch" :kind :washer-extractor
                 :last-inspection-day 90 :as-of-day 100
                 :cycle-running? false
                 :left-at-day 80 :posted-retention-days 7
                 :suspended? false :property-held? false
                 :jurisdiction "JPN" :status :in-service}
    "machine-2" {:id "machine-2" :site "Atlantis branch" :kind :dryer
                 :last-inspection-day 90 :as-of-day 100
                 :cycle-running? false
                 :left-at-day 80 :posted-retention-days 7
                 :suspended? false :property-held? false
                 :jurisdiction "ATL" :status :in-service}
    "machine-3" {:id "machine-3" :site "Kawagishi branch" :kind :washer-extractor
                 :last-inspection-day 50 :as-of-day 100
                 :cycle-running? false
                 :left-at-day 80 :posted-retention-days 7
                 :suspended? false :property-held? false
                 :jurisdiction "JPN" :status :in-service}
    "machine-4" {:id "machine-4" :site "Sakura-dori branch" :kind :dryer
                 :last-inspection-day 90 :as-of-day 100
                 :cycle-running? true
                 :left-at-day 80 :posted-retention-days 7
                 :suspended? false :property-held? false
                 :jurisdiction "JPN" :status :in-service}
    "machine-5" {:id "machine-5" :site "Kawagishi branch" :kind :washer-extractor
                 :last-inspection-day 90 :as-of-day 100
                 :cycle-running? false
                 :left-at-day 98 :posted-retention-days 7
                 :suspended? false :property-held? false
                 :jurisdiction "JPN" :status :in-service}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- suspend-machine! [s machine-id]
  (let [m (machine s machine-id)
        seq-n (next-suspension-sequence s (:jurisdiction m))
        result (registry/register-machine-suspension machine-id (:jurisdiction m) seq-n)]
    {:result result
     :machine-patch {:suspended? true
                     :suspension-number (get result "suspension_number")}}))

(defn- hold-property! [s machine-id]
  (let [m (machine s machine-id)
        seq-n (next-custody-sequence s (:jurisdiction m))
        result (registry/register-abandoned-property-hold machine-id (:jurisdiction m) seq-n)]
    {:result result
     :machine-patch {:property-held? true
                     :custody-number (get result "custody_number")}}))

;; ----------------------------- MemStore -----------------------------

(defrecord MemStore [a]
  Store
  (machine [_ id] (get-in @a [:machines id]))
  (all-machines [_] (sort-by :id (vals (:machines @a))))
  (inspection-screening-of [_ id] (get-in @a [:inspection-screenings id]))
  (sanitation-plan-of [_ id] (get-in @a [:sanitation-plans id]))
  (ledger [_] (:ledger @a))
  (suspension-history [_] (:suspensions @a))
  (custody-history [_] (:custodies @a))
  (next-suspension-sequence [_ j] (get-in @a [:suspension-sequences j] 0))
  (next-custody-sequence [_ j] (get-in @a [:custody-sequences j] 0))
  (machine-already-suspended? [_ id] (boolean (get-in @a [:machines id :suspended?])))
  (property-already-held? [_ id] (boolean (get-in @a [:machines id :property-held?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :machine/upsert
      (swap! a update-in [:machines (:id value)] merge value)

      :sanitation-plan/set
      (swap! a assoc-in [:sanitation-plans (first path)] payload)

      :inspection-screening/set
      (swap! a assoc-in [:inspection-screenings (first path)] payload)

      :machine/mark-suspended
      (let [machine-id (first path)
            {:keys [result machine-patch]} (suspend-machine! s machine-id)
            j (:jurisdiction (machine s machine-id))]
        (swap! a (fn [st]
                   (-> st
                       (update-in [:suspension-sequences j] (fnil inc 0))
                       (update-in [:machines machine-id] merge machine-patch)
                       (update :suspensions registry/append result))))
        result)

      :machine/mark-property-held
      (let [machine-id (first path)
            {:keys [result machine-patch]} (hold-property! s machine-id)
            j (:jurisdiction (machine s machine-id))]
        (swap! a (fn [st]
                   (-> st
                       (update-in [:custody-sequences j] (fnil inc 0))
                       (update-in [:machines machine-id] merge machine-patch)
                       (update :custodies registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-machines [s machines] (when (seq machines) (swap! a assoc :machines machines)) s))

(defn seed-db
  "A MemStore seeded with the demo machine set. The deterministic
  default -- the same shape every sibling actor exposes, which is what
  lets the 営み OS adapter be a three-line shim."
  []
  (->MemStore (atom (assoc (demo-data)
                           :sanitation-plans {} :inspection-screenings {}
                           :ledger [] :suspension-sequences {} :suspensions []
                           :custody-sequences {} :custodies []))))
