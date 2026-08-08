(ns coinlaundry.phase-test
  "The permanent invariants of the rollout phase table, plus the one
  invariant that distinguishes this actor from every bailment sibling:
  **there is no return op, because nothing was handed over.**"
  (:require [clojure.test :refer [deftest is testing]]
            [coinlaundry.phase :as phase]
            [coinlaundry.governor :as governor]))

(deftest this-is-not-the-bailment-shape
  ;; 9601 / 9522 / 9523 / 9601-carpet / 4520-carwash all end with a
  ;; return op. A self-service laundry has nothing to return -- the
  ;; customer never handed anything over. Copying the sibling shape here
  ;; would have invented a custody relationship that does not exist.
  (testing "no op in the vocabulary returns anything to a customer"
    (doseq [op governor/allowed-ops]
      (is (not (re-find #"return" (name op)))
          (str op " looks like a return op; this actor must not have one"))))
  (testing "and the two actuations are about the machine and about property left in it"
    (is (= #{:actuation/suspend-machine :actuation/hold-abandoned-property}
           governor/high-stakes))))

(deftest actuations-are-never-auto-eligible-at-any-phase
  (let [auto (phase/auto-eligible-ops)]
    (doseq [op [:actuation/suspend-machine :actuation/hold-abandoned-property]]
      (testing (str op " is absent from every phase's :auto set")
        (is (not (contains? auto op)))))
    (testing "screening is likewise never auto-eligible"
      (is (not (contains? auto :inspection/screen))))
    (testing "but :auto is not empty -- registering a machine may auto-commit"
      (is (= #{:machine/register} auto)))))

(deftest every-phase-writes-set-is-a-subset-of-write-ops
  (doseq [[p {:keys [writes auto]}] phase/phases]
    (testing (str "phase " p)
      (is (every? phase/write-ops writes))
      (is (every? writes auto) ":auto must be a subset of :writes"))))

(deftest phase-0-writes-nothing
  (is (empty? (:writes (get phase/phases 0)))))

(deftest a-governor-hold-survives-every-phase
  (doseq [p (keys phase/phases)]
    (is (= :hold (:disposition (phase/gate p {:op :machine/register} :hold))))))

(deftest a-disabled-write-holds-with-a-reason
  (let [{:keys [disposition reason]}
        (phase/gate 1 {:op :actuation/suspend-machine} :commit)]
    (is (= :hold disposition))
    (is (= :phase-disabled reason))))

(deftest a-clean-actuation-escalates-rather-than-commits
  (let [{:keys [disposition reason]}
        (phase/gate 3 {:op :actuation/hold-abandoned-property} :commit)]
    (is (= :escalate disposition))
    (is (= :phase-approval reason))))

(deftest registration-may-auto-commit-at-phase-3-only
  (is (= :commit (:disposition (phase/gate 3 {:op :machine/register} :commit))))
  (is (= :escalate (:disposition (phase/gate 2 {:op :machine/register} :commit)))))

(deftest verdict-mapping-prefers-the-most-cautious-reading
  (is (= :hold (phase/verdict->disposition {:hard? true :escalate? true})))
  (is (= :escalate (phase/verdict->disposition {:hard? false :escalate? true})))
  (is (= :commit (phase/verdict->disposition {:hard? false :escalate? false}))))

(deftest default-phase-is-declared
  (is (contains? phase/phases phase/default-phase)))
