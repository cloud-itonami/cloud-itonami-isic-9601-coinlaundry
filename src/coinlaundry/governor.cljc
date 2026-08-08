(ns coinlaundry.governor
  "The **Coin Laundry Governor** -- an independent censor, a different
  system than the advisor it censors. It never asks the advisor whether
  a proposal is safe; it recomputes what it can from what the operator
  already recorded, and holds when it cannot.

  ## What is absent from the vocabulary, not merely gated

  `allowed-ops` is the whole vocabulary -- five ops. **No op disposes of,
  sells, or takes ownership of property left in a machine, and no op
  settles a damage claim.**

  That first absence is the important one. Taking abandoned laundry into
  custody is already the single moment an unattended operator holds
  someone else's property; *disposing* of it is governed by lost-property
  law and often requires involving the police. An actor that could hold
  and also dispose would be one confident proposal away from throwing
  away a stranger's clothes.

  ## The checks

    1. Spec basis missing        -- a jurisdiction with no sanitation basis
                                    on file cannot be operated in. HARD.
    2. Evidence incomplete       -- for either actuation, the jurisdiction's
                                    required records must actually be present.
    3. Sanitation inspection     -- recomputed from the site's own last
       overdue                     inspection day against the jurisdiction's
                                    own interval. **An unknown interval reads
                                    as overdue**, never as 'no obligation'.
    4. Machine cycle running     -- suspending a machine whose own recorded
                                    state says a cycle is running. Someone's
                                    clothes are inside it.
    5. Abandonment period not    -- property may not be taken into custody
       elapsed                     before the site's own posted retention
                                    period has run. Missing data holds.
    6/7. Already suspended /     -- double-actuation guards off dedicated
       already held                booleans, never a `:status` value.
    8. Scope excluded            -- the advisor's own prose reaching for
                                    disposal, sale, ownership or liability.
                                    **A substring scan cannot tell a claim
                                    from a denial**: a rationale saying
                                    \"this actor cannot dispose of it\"
                                    contains the term and trips the gate.
                                    The fix is on the advisor's side (say
                                    the boundary in the docstring, not in
                                    scanned text), not a smarter scanner --
                                    negation detection would be the kind of
                                    cleverness a gate must not depend on.
                                    Measured 2026-08-08 by the demo.

  ## Why the checks are all about elapsed time

  The bailment siblings (9601, 9522, 9523, 9601-carpet, 4520-carwash)
  hold a physical thing, so their strongest checks are material
  incompatibility and arithmetic identity. **An unattended site holds
  nothing**, so its risks are temporal: has the inspection interval
  passed, has the retention period run, is a cycle running right now.
  That is a different family of check, and copying the siblings' would
  have produced gates that never fire here.

  ## high-stakes is a set of OPS, not of advisor-reported stakes

  Following 9601 (this repo's parent) rather than 9522/9523."
  (:require [clojure.string :as str]
            [coinlaundry.facts :as facts]
            [coinlaundry.registry :as registry]
            [coinlaundry.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a scope
  violation by construction. **There is no `:actuation/return-*` op**
  because the customer never handed anything over."
  #{:machine/register :sanitation-plan/verify :inspection/screen
    :actuation/suspend-machine :actuation/hold-abandoned-property})

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Cutting a machine a customer may be using, and taking a stranger's
  property into custody."
  #{:actuation/suspend-machine :actuation/hold-abandoned-property})

(def scope-excluded-terms
  "Case-insensitive substrings marking a proposal as reaching for a
  permanently out-of-scope decision."
  ;; NOTE: list the inflections explicitly rather than a stem. "dispos"
  ;; would also match "disposition", which is a legitimate word in this
  ;; fleet's own vocabulary -- a scope gate that fires on the governor's
  ;; own terminology is worse than one that misses a phrasing.
  ;; The first draft had only "dispose of" and let "disposed of"
  ;; through; the contract test caught it.
  ["dispose" "disposed" "disposal" "廃棄" "処分し" "処分する" "処分予定"
   "sell the" "sold at auction" "売却"
   "ownership transferred" "所有権を移転" "所有権が移る"
   "damage claim approved" "損害賠償を認め"])

;; ----------------------------- checks -----------------------------

(defn- op-not-allowed-violations
  [{:keys [op]}]
  (when-not (contains? allowed-ops op)
    [{:rule :op-not-allowed
      :detail (str op " はこの actor の語彙に存在しない")}]))

(defn- spec-basis-violations
  [{:keys [op]} proposal]
  (when (contains? #{:sanitation-plan/verify
                     :actuation/suspend-machine
                     :actuation/hold-abandoned-property} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basis(衛生等管理要領)の引用が無い提案は無人店舗の運営基準として扱えない"}]))))

(defn- evidence-incomplete-violations
  [{:keys [op subject]} st]
  (when (contains? #{:actuation/suspend-machine :actuation/hold-abandoned-property} op)
    (let [m (store/machine st subject)
          plan (store/sanitation-plan-of st subject)]
      (when-not (and plan
                     (facts/required-evidence-satisfied?
                      (:jurisdiction m) (:checklist plan)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(清掃・消毒記録/掲示記録/保守点検記録/残置物取扱記録)が充足していない"}]))))

(defn- sanitation-inspection-overdue-violations
  "Recomputed from the machine's own last-inspection day against its
  jurisdiction's own interval. Evaluated for the screening op AND for
  both actuations -- an overdue site must not keep acting."
  [{:keys [op subject]} st]
  (when (contains? #{:inspection/screen
                     :actuation/suspend-machine
                     :actuation/hold-abandoned-property} op)
    (let [m (store/machine st subject)
          interval (facts/inspection-interval-days (:jurisdiction m))]
      (when (registry/sanitation-inspection-overdue? m interval)
        [{:rule :sanitation-inspection-overdue
          :detail (str subject " の清掃・消毒点検が法域の間隔(" (or interval "不明")
                       "日)を超過している（最終点検 " (:last-inspection-day m) " / 基準日 "
                       (:as-of-day m) "）")}]))))

(defn- machine-cycle-running-violations
  "For `:actuation/suspend-machine`, recompute from the machine's own
  recorded state. **Someone's clothes are inside a running machine** --
  this is not an administrative act."
  [{:keys [op subject]} st]
  (when (= op :actuation/suspend-machine)
    (let [m (store/machine st subject)]
      (when (true? (:cycle-running? m))
        [{:rule :machine-cycle-running
          :detail (str subject " は運転中。利用者の洗濯物が中にある状態での停止提案は進められない")}]))))

(defn- abandonment-period-not-elapsed-violations
  "For `:actuation/hold-abandoned-property`, the site's own posted
  retention period must have run. Missing data holds -- an unknown
  period has not been shown to have elapsed."
  [{:keys [op subject]} st]
  (when (= op :actuation/hold-abandoned-property)
    (let [m (store/machine st subject)]
      (when (registry/abandonment-period-not-elapsed? m)
        [{:rule :abandonment-period-not-elapsed
          :detail (str subject " の掲示保管期間(" (or (:posted-retention-days m) "不明")
                       "日)が未経過。残置物を保管に移す提案は進められない")}]))))

(defn- already-suspended-violations
  [{:keys [op subject]} st]
  (when (= op :actuation/suspend-machine)
    (when (store/machine-already-suspended? st subject)
      [{:rule :already-suspended :detail (str subject " は既に停止済み")}])))

(defn- already-held-violations
  [{:keys [op subject]} st]
  (when (= op :actuation/hold-abandoned-property)
    (when (store/property-already-held? st subject)
      [{:rule :already-held :detail (str subject " の残置物は既に保管記録済み")}])))

(defn- scope-exclusion-violations
  [proposal]
  (let [text (str (:summary proposal) " " (:rationale proposal))
        lower (str/lower-case text)]
    (when-let [hit (first (filter #(str/includes? lower (str/lower-case (str %)))
                                  scope-excluded-terms))]
      [{:rule :scope-excluded
        :detail (str "恒久的にスコープ外の判断に触れる文言を含む: " hit)}])))

(defn check
  "Censors a CoinLaundryAdvisor proposal against the governor rules."
  [request _context proposal st]
  (let [hard (into []
                   (concat (op-not-allowed-violations request)
                           (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (sanitation-inspection-overdue-violations request st)
                           (machine-cycle-running-violations request st)
                           (abandonment-period-not-elapsed-violations request st)
                           (already-suspended-violations request st)
                           (already-held-violations request st)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:op request)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  [request context verdict]
  {:t           :governor-hold
   :op          (:op request)
   :actor       (:actor-id context)
   :subject     (:subject request)
   :disposition :hold
   :basis       (mapv :rule (:violations verdict))
   :violations  (:violations verdict)
   :confidence  (:confidence verdict)})
