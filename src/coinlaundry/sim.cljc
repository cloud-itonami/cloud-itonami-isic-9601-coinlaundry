(ns coinlaundry.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean machine through
  registration -> sanitation-plan verification -> inspection screening ->
  suspension (escalate/approve/commit) -> abandoned-property hold
  (escalate/approve/commit), then shows every HARD-hold scenario:

    - a jurisdiction with no spec-basis (machine-2, \"ATL\")
    - a sanitation inspection 50 days old against a 30-day interval
      (machine-3)
    - suspending a machine with a cycle running (machine-4)
    - taking property into custody before the posted period elapsed
      (machine-5)
    - an op outside the closed vocabulary (there is no disposal op)
    - a proposal whose prose reaches for disposal

  Deterministic and offline -- the actor never reads a clock; all
  day-stamps come from the seed."
  (:require [langgraph.graph :as g]
            [coinlaundry.store :as store]
            [coinlaundry.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :site-manager :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== machine/register machine-1 (JPN, idle, inspected day 90) ==")
    (exec-op actor "t1" {:op :machine/register :subject "machine-1"
                         :patch {:id "machine-1" :site "Sakura-dori branch"}} operator)

    (println "== sanitation-plan/verify machine-1 (escalates -- approves) ==")
    (exec-op actor "t2" {:op :sanitation-plan/verify :subject "machine-1"} operator)
    (approve! actor "t2")

    (println "== inspection/screen machine-1 (within interval; approves) ==")
    (exec-op actor "t3" {:op :inspection/screen :subject "machine-1"} operator)
    (approve! actor "t3")

    (println "== actuation/suspend-machine machine-1 (never auto) ==")
    (exec-op actor "t4" {:op :actuation/suspend-machine :subject "machine-1"} operator)
    (approve! actor "t4")

    (println "== actuation/hold-abandoned-property machine-1 (never auto) ==")
    (exec-op actor "t5" {:op :actuation/hold-abandoned-property :subject "machine-1"} operator)
    (approve! actor "t5")

    (println "== HOLD: no spec-basis (machine-2, ATL) ==")
    (exec-op actor "h1" {:op :sanitation-plan/verify :subject "machine-2"} operator)

    (println "== HOLD: sanitation inspection overdue (machine-3, 50d vs 30d) ==")
    (exec-op actor "h2" {:op :inspection/screen :subject "machine-3"} operator)

    (println "== HOLD: cycle running (machine-4) ==")
    (exec-op actor "h3" {:op :actuation/suspend-machine :subject "machine-4"} operator)

    (println "== HOLD: abandonment period not elapsed (machine-5, 2d of 7d) ==")
    (exec-op actor "h4" {:op :actuation/hold-abandoned-property :subject "machine-5"} operator)

    (println "== HOLD: op outside the closed vocabulary (no disposal op exists) ==")
    (exec-op actor "h5" {:op :actuation/dispose-abandoned-property :subject "machine-1"} operator)

    (println "== HOLD: double suspension (machine-1 already suspended above) ==")
    (exec-op actor "h6" {:op :actuation/suspend-machine :subject "machine-1"} operator)

    (println "\n== ledger (append-only) ==")
    (doseq [f (store/ledger db)]
      (println " " (:t f) (:op f) (:subject f) (or (:basis f) "")))))
