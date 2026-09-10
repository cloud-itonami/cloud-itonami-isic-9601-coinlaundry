(ns coinlaundry.governor-contract-test
  "Every HARD check must actually fire, and each must fire for its own
  reason. A gate that cannot be shown to refuse is theatre."
  (:require [clojure.test :refer [deftest is testing]]
            [coinlaundry.governor :as governor]
            [coinlaundry.registry :as registry]
            [coinlaundry.facts :as facts]
            [coinlaundry.store :as store]))

(def ctx {:actor-id "op-1" :actor-role :site-manager :phase 3})

(defn- db [] (store/seed-db))

(defn- clean-proposal [op subject]
  {:op op :summary "ok" :rationale "ok"
   :cites ["コインオペレーションクリーニング営業施設における衛生等管理要領（厚生労働省通知）"]
   :effect :noop :value {:machine-id subject} :confidence 0.9})

(defn- rules-of [verdict] (set (map :rule (:violations verdict))))

;; ----------------------------- what is absent, not gated -----------------------------

(deftest no-disposal-or-sale-or-liability-op-exists
  (doseq [absent [:actuation/dispose-abandoned-property :actuation/sell-abandoned-property
                  :actuation/transfer-ownership :actuation/settle-damage-claim]]
    (is (not (contains? governor/allowed-ops absent))
        "these are absent from the vocabulary, not merely gated")))

(deftest an-op-outside-the-allowlist-is-hard-held
  (let [v (governor/check {:op :actuation/dispose-abandoned-property :subject "machine-1"}
                          ctx (clean-proposal :actuation/dispose-abandoned-property "machine-1") (db))]
    (is (:hard? v))
    (is (contains? (rules-of v) :op-not-allowed))))

(deftest prose-describing-disposal-is-hard-held
  (let [p (assoc (clean-proposal :machine/register "machine-1")
                 :rationale "items left over 30 days will be disposed of")
        v (governor/check {:op :machine/register :subject "machine-1"} ctx p (db))]
    (is (:hard? v))
    (is (contains? (rules-of v) :scope-excluded))))

;; ----------------------------- spec basis -----------------------------

(deftest a-proposal-with-no-cites-is-hard-held
  (let [p (assoc (clean-proposal :sanitation-plan/verify "machine-2") :cites [])
        v (governor/check {:op :sanitation-plan/verify :subject "machine-2"} ctx p (db))]
    (is (:hard? v))
    (is (contains? (rules-of v) :no-spec-basis))))

(deftest an-unseeded-jurisdiction-can-never-satisfy-its-evidence
  (is (nil? (facts/required-evidence-satisfied? "ATL" ["anything"]))))

;; ----------------------------- elapsed-time checks -----------------------------

(deftest an-overdue-sanitation-inspection-is-recomputed-and-held
  ;; machine-3: last inspected day 50, as-of 100, interval 30.
  (let [v (governor/check {:op :inspection/screen :subject "machine-3"}
                          ctx (clean-proposal :inspection/screen "machine-3") (db))]
    (is (:hard? v))
    (is (contains? (rules-of v) :sanitation-inspection-overdue))))

(deftest an-unknown-interval-reads-as-overdue-never-as-no-obligation
  (is (registry/sanitation-inspection-overdue?
       {:last-inspection-day 99 :as-of-day 100} nil)
      "a jurisdiction with no interval on file must not pass silently")
  (is (registry/sanitation-inspection-overdue?
       {:last-inspection-day nil :as-of-day 100} 30)
      "a machine never inspected must not pass silently")
  (is (not (registry/sanitation-inspection-overdue?
            {:last-inspection-day 90 :as-of-day 100} 30))))

(deftest holding-property-before-the-posted-period-elapsed-is-held
  ;; machine-5: left day 98, as-of 100, posted 7 days -> 2 of 7.
  (let [v (governor/check {:op :actuation/hold-abandoned-property :subject "machine-5"}
                          ctx (clean-proposal :actuation/hold-abandoned-property "machine-5") (db))]
    (is (:hard? v))
    (is (contains? (rules-of v) :abandonment-period-not-elapsed))))

(deftest missing-abandonment-data-holds
  (is (registry/abandonment-period-not-elapsed? {:left-at-day nil :posted-retention-days 7 :as-of-day 100}))
  (is (registry/abandonment-period-not-elapsed? {:left-at-day 80 :posted-retention-days nil :as-of-day 100}))
  (is (not (registry/abandonment-period-not-elapsed?
            {:left-at-day 80 :posted-retention-days 7 :as-of-day 100}))))

;; ----------------------------- the running machine -----------------------------

(deftest suspending-a-running-machine-is-hard-held
  ;; machine-4 has a cycle running: someone's clothes are inside it.
  (let [v (governor/check {:op :actuation/suspend-machine :subject "machine-4"}
                          ctx (clean-proposal :actuation/suspend-machine "machine-4") (db))]
    (is (:hard? v))
    (is (contains? (rules-of v) :machine-cycle-running))))

;; ----------------------------- evidence -----------------------------

(deftest an-actuation-without-a-verified-sanitation-plan-is-hard-held
  (let [v (governor/check {:op :actuation/suspend-machine :subject "machine-1"}
                          ctx (clean-proposal :actuation/suspend-machine "machine-1") (db))]
    (is (:hard? v))
    (is (contains? (rules-of v) :evidence-incomplete))))

(deftest a-complete-sanitation-plan-clears-the-evidence-check
  (let [st (db)]
    (store/commit-record! st {:effect :sanitation-plan/set :path ["machine-1"]
                              :payload {:jurisdiction "JPN"
                                        :checklist (facts/required-evidence "JPN")}})
    (let [v (governor/check {:op :actuation/suspend-machine :subject "machine-1"}
                            ctx (clean-proposal :actuation/suspend-machine "machine-1") st)]
      (is (not (contains? (rules-of v) :evidence-incomplete))))))

;; ----------------------------- double actuation -----------------------------

(deftest the-same-machine-cannot-be-suspended-or-held-twice
  (let [st (db)]
    (store/commit-record! st {:effect :sanitation-plan/set :path ["machine-1"]
                              :payload {:jurisdiction "JPN"
                                        :checklist (facts/required-evidence "JPN")}})
    (store/commit-record! st {:effect :machine/mark-suspended :path ["machine-1"]})
    (store/commit-record! st {:effect :machine/mark-property-held :path ["machine-1"]})
    (is (contains? (rules-of (governor/check
                              {:op :actuation/suspend-machine :subject "machine-1"}
                              ctx (clean-proposal :actuation/suspend-machine "machine-1") st))
                   :already-suspended))
    (is (contains? (rules-of (governor/check
                              {:op :actuation/hold-abandoned-property :subject "machine-1"}
                              ctx (clean-proposal :actuation/hold-abandoned-property "machine-1") st))
                   :already-held))))

;; ----------------------------- high stakes -----------------------------

(deftest high-stakes-is-decided-on-the-op-not-the-advisors-self-report
  (let [st (db)]
    (store/commit-record! st {:effect :sanitation-plan/set :path ["machine-1"]
                              :payload {:jurisdiction "JPN"
                                        :checklist (facts/required-evidence "JPN")}})
    (testing "an actuation escalates even when the advisor declares no stake"
      (let [p (dissoc (clean-proposal :actuation/suspend-machine "machine-1") :stake)
            v (governor/check {:op :actuation/suspend-machine :subject "machine-1"} ctx p st)]
        (is (:high-stakes? v))
        (is (:escalate? v))
        (is (not (:ok? v)))))
    (testing "and registration does not escalate on stakes"
      (let [v (governor/check {:op :machine/register :subject "machine-1"} ctx
                              (clean-proposal :machine/register "machine-1") st)]
        (is (not (:high-stakes? v)))
        (is (:ok? v))))))
