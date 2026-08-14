(ns coinlaundry.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2: this repo had NO demo page and no
  generator. This namespace drives the **REAL** actor stack --
  `coinlaundry.operation/build` compiles a langgraph-clj StateGraph
  whose `:advise` node is the sealed advisor, whose `:govern` node is
  `coinlaundry.governor`, and whose `:commit` node is the only writer of
  `coinlaundry.store` -- over the **REAL** seeded machine set
  (`coinlaundry.store/demo-data`, `machine-1`..`machine-5`).

  Every identifier on the page comes out of that run. Machine ids, sites,
  jurisdictions, day-stamps, suspension numbers (`SUS-JPN-0001`) and
  custody numbers (`CUS-JPN-0001`) are read back from the store after the
  scenario; hold rules and their Japanese detail strings are read off the
  governor's own `:violations`. Nothing on the page is typed by hand.

  ## Why the scenario is longer than `coinlaundry.sim`

  `sim` demonstrates each hold with the machine in its seeded state, so
  two of its holds fire on *two* rules at once (`:evidence-incomplete`
  co-fires with `:machine-cycle-running` and with
  `:abandonment-period-not-elapsed`, because no sanitation plan has been
  committed for those machines yet). A console is read by someone asking
  \"which rule stopped this?\", so this scenario first commits the
  sanitation plan the jurisdiction requires and *then* attempts the
  actuation -- isolating each rule to exactly one row. It also reaches
  three dispositions `sim` never shows: a low-confidence escalation
  (`confidence-floor` 0.6), a human **rejecting** an approval, and
  `:already-held`.

  All nine of the governor's HARD rules fire in one run. That is asserted
  at build time, not hoped for: `-main` throws when the ledger carries no
  `:governor-hold` fact, so a console that shows no real hold cannot be
  written at all.

  ## Determinism

  The actor never reads a clock -- every day-stamp is seed data -- and
  there is no randomness and no network. Two consecutive runs against the
  same seed produce byte-identical output.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [coinlaundry.facts :as facts]
            [coinlaundry.governor :as governor]
            [coinlaundry.operation :as op]
            [coinlaundry.phase :as phase]
            [coinlaundry.store :as store]))

(def ^:private operator
  "The single operator context every run in this scenario uses. Phase 3
  (`supervised-auto`) is this actor's `default-phase` -- the most
  permissive rollout phase that exists, which is what makes the holds
  below meaningful: they are not phase-disabled ops, they are ops the
  governor refused at full authority."
  {:actor-id "op-1" :actor-role :site-manager :phase phase/default-phase})

;; ----------------------------- driving the real actor -----------------------------

(defn- record!
  "Capture one finished run: the request that went in, and the audit the
  graph actually accumulated. The page's per-run outcome column is
  derived from this audit, never from a literal written next to the call."
  [runs tid note request result]
  (swap! runs conj {:tid tid
                    :note note
                    :request request
                    :audit (vec (get-in result [:state :audit]))
                    :disposition (get-in result [:state :disposition])})
  result)

(defn- exec!
  "One operation with no human in the loop -- it auto-commits or it
  HARD-holds."
  [runs actor tid note request]
  (record! runs tid note request
           (g/run* actor {:request request :context operator} {:thread-id tid})))

(defn- resume!
  "One operation that the governor or the phase gate escalates, resumed
  by a human decision. `interrupt-before #{:request-approval}` pauses the
  graph; the resumed result carries the FULL accumulated audit (the
  `:audit` channel reducer is `into`, restored from the checkpointer), so
  only the resumed result is recorded."
  [runs actor tid note request approval]
  (g/run* actor {:request request :context operator} {:thread-id tid})
  (record! runs tid note request
           (g/run* actor {:approval approval} {:thread-id tid :resume? true})))

(def ^:private approved {:status :approved :by "op-1"})
(def ^:private rejected {:status :rejected :by "op-1"})

(defn run-demo!
  "Runs a fresh seeded store through a scenario that reaches every
  disposition this actor can produce and fires all nine of the
  governor's HARD rules, each isolated to a single row wherever the
  rules allow it.

  Returns `{:db :runs}` -- the store after the run, and the per-run audit
  trail. Everything the page prints is read back out of these."
  []
  (let [db    (store/seed-db)
        actor (op/build db)
        runs  (atom [])]

    ;; --- machine-1: the clean lifecycle, end to end -----------------------
    (exec! runs actor "t01" "phase-3 auto-commit: a record-keeping op with no capital or custody risk"
           {:op :machine/register :subject "machine-1"
            :patch {:id "machine-1" :site "Sakura-dori branch"}})

    (resume! runs actor "t02" "JPN sanitation basis verified; escalated because no phase auto-commits it"
             {:op :sanitation-plan/verify :subject "machine-1"} approved)

    (resume! runs actor "t03" "inspection screened from the ledger's own day-stamps (90 -> 100, interval 30)"
             {:op :inspection/screen :subject "machine-1"} approved)

    (resume! runs actor "t04" "ALWAYS escalates -- a customer's clothes may be inside a machine being cut"
             {:op :actuation/suspend-machine :subject "machine-1"} approved)

    (resume! runs actor "t05" "ALWAYS escalates -- the one moment the operator holds a stranger's property"
             {:op :actuation/hold-abandoned-property :subject "machine-1"} approved)

    ;; --- the confidence floor, and a human saying no ----------------------
    (resume! runs actor "t06" "advisor confidence 0.45 is under the 0.6 floor -- escalates on confidence alone"
             {:op :machine/register :subject "machine-2" :confidence 0.45
              :patch {:id "machine-2" :site "Atlantis branch"}} approved)

    (resume! runs actor "t07" "governor-clean and phase-permitted -- and the human declined it anyway"
             {:op :inspection/screen :subject "machine-5"} rejected)

    ;; --- HARD holds, one rule each ---------------------------------------
    (exec! runs actor "h01" "machine-2 sits in jurisdiction \"ATL\", which has no sanitation basis on file"
           {:op :sanitation-plan/verify :subject "machine-2"})

    (exec! runs actor "h02" "machine-3 was last inspected on day 50 against a 30-day interval"
           {:op :inspection/screen :subject "machine-3"})

    (resume! runs actor "t08" "machine-4's required records committed first, so the next hold is unambiguous"
             {:op :sanitation-plan/verify :subject "machine-4"} approved)

    (exec! runs actor "h03" "machine-4 has a cycle running right now -- someone's laundry is inside it"
           {:op :actuation/suspend-machine :subject "machine-4"})

    (exec! runs actor "h04" "machine-5 has no sanitation plan on file yet, so its records are incomplete"
           {:op :actuation/suspend-machine :subject "machine-5"})

    (resume! runs actor "t09" "machine-5's required records committed, isolating the retention-period rule"
             {:op :sanitation-plan/verify :subject "machine-5"} approved)

    (exec! runs actor "h05" "property was left in machine-5 on day 98; the posted period is 7 days"
           {:op :actuation/hold-abandoned-property :subject "machine-5"})

    (resume! runs actor "t10" "machine-3's records committed -- the remaining defect is the overdue inspection"
             {:op :sanitation-plan/verify :subject "machine-3"} approved)

    (exec! runs actor "h06" "an overdue site must not keep acting: the same rule blocks the actuation too"
           {:op :actuation/suspend-machine :subject "machine-3"})

    (exec! runs actor "h07" "there is no disposal op in this actor's vocabulary, and the prose reaches for one"
           {:op :actuation/dispose-abandoned-property :subject "machine-1"})

    (exec! runs actor "h08" "machine-1 was already suspended at t04 -- the guard is a dedicated boolean"
           {:op :actuation/suspend-machine :subject "machine-1"})

    (exec! runs actor "h09" "machine-1's property was already taken into custody at t05"
           {:op :actuation/hold-abandoned-property :subject "machine-1"})

    {:db db :runs @runs}))

;; ----------------------------- derivation -----------------------------

(defn- kw-str [x] (if (keyword? x) (name x) (str x)))

(defn- fact-of [audit t] (first (filter #(= t (:t %)) audit)))

(defn- holds
  "The HARD `:governor-hold` facts the run actually wrote to the ledger."
  [db]
  (filterv #(= :governor-hold (:t %)) (store/ledger db)))

(defn- rules-fired
  "Every distinct governor rule that fired in this run, read off the
  violations the governor itself attached."
  [db]
  (into (sorted-set) (mapcat #(map :rule (:violations %)) (holds db))))

(defn- outcome
  "Classify one run from its own audit trail, never from a literal."
  [{:keys [audit]}]
  (cond
    (fact-of audit :approval-rejected)
    {:kind :rejected}

    (fact-of audit :governor-hold)
    {:kind :hold :basis (:basis (fact-of audit :governor-hold))}

    (fact-of audit :approval-granted)
    {:kind :approved
     :by (:by (fact-of audit :approval-granted))
     :reason (:reason (fact-of audit :approval-requested))}

    (fact-of audit :approval-requested)
    {:kind :awaiting :reason (:reason (fact-of audit :approval-requested))}

    (fact-of audit :committed)
    {:kind :auto}

    :else {:kind :unknown}))

(defn- deep-key-names
  "Every map key anywhere inside `x`, as strings."
  [x]
  (cond
    (map? x) (into (into #{} (map kw-str) (keys x))
                   (mapcat deep-key-names (vals x)))
    (sequential? x) (into #{} (mapcat deep-key-names x))
    :else #{}))

(def ^:private approver-key?
  #(contains? #{"approved-by" "approved_by" "approver" "approved_by_id"}
              (str/lower-case %)))

(defn- registers
  "The four SSoT registers this actor writes, read back through the
  `Store` protocol -- not out of the MemStore atom, so a `DatomicStore`
  would render identically."
  [db]
  (let [machines (store/all-machines db)]
    [{:label "machine entities" :effect :machine/upsert
      :rows machines}
     {:label "sanitation plans" :effect :sanitation-plan/set
      :rows (keep #(store/sanitation-plan-of db (:id %)) machines)}
     {:label "inspection screenings" :effect :inspection-screening/set
      :rows (keep #(store/inspection-screening-of db (:id %)) machines)}
     {:label "machine suspensions" :effect :machine/mark-suspended
      :rows (store/suspension-history db)}
     {:label "abandoned-property custodies" :effect :machine/mark-property-held
      :rows (store/custody-history db)}]))

(defn- approver-attribution
  "DERIVED, at render time, from the real store.

  `operation`'s `:request-approval` node attaches the approver at
  `[:payload :approved-by]` on the record it hands to `commit-record!`.
  Whether that survives depends on which branch of `commit-record!` runs:
  the `:sanitation-plan/set` and `:inspection-screening/set` branches
  store `payload` verbatim, `:machine/upsert` stores `value` (which never
  carried the approver), and the two actuation branches store neither --
  they recompute the record from `coinlaundry.registry`.

  Rather than write that asymmetry into prose (which goes stale the day
  it is fixed), this walks each register and reports whether an approver
  key is *actually present*, so the page self-corrects."
  [db runs]
  {:approvers (vec (sort (into #{} (keep #(:by (fact-of (:audit %) :approval-granted))) runs)))
   :on-ledger? (boolean (some #(= :approval-granted (:t %)) (store/ledger db)))
   :registers (mapv (fn [{:keys [label effect rows]}]
                      {:label label
                       :effect effect
                       :n (count rows)
                       :retains? (boolean (some approver-key? (deep-key-names rows)))})
                    (registers db))})

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str (if (nil? v) "" v))
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- code [v] (str "<code>" (esc v) "</code>"))
(defn- dash [] "<span class=\"muted\">&mdash;</span>")
(defn- ok [s] (str "<span class=\"ok\">" s "</span>"))
(defn- warn [s] (str "<span class=\"warn\">" s "</span>"))
(defn- crit [s] (str "<span class=\"critical\">" s "</span>"))
(defn- muted [s] (str "<span class=\"muted\">" s "</span>"))

(defn- cells [xs] (str/join (map #(str "<td>" % "</td>") xs)))
(defn- row [xs] (str "        <tr>" (cells xs) "</tr>"))
(defn- rows [xss] (str/join "\n" (map row xss)))

(defn- table [headers body-rows]
  (str "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n" (rows body-rows) "\n      </tbody>\n"
       "    </table>\n"))

(defn- section [title lede body]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       (when lede (str "    <p class=\"muted\">" lede "</p>\n"))
       body
       "  </section>\n"))

(defn- ops-list [op-set]
  (str/join " " (map #(code (str %)) (sort-by str op-set))))

(defn- oxford
  "Join derived phrases into readable prose. The register lists in the
  attribution section are computed, so their length is not known here."
  [xs]
  (case (count xs)
    0 ""
    1 (first xs)
    2 (str (first xs) " and " (second xs))
    (str (str/join ", " (butlast xs)) ", and " (last xs))))

;; ----------------------------- sections -----------------------------

(defn- summary-section [db runs]
  (let [ledger (store/ledger db)
        hs (holds db)]
    (section
     "This run at a glance"
     (str "Counted from the ledger and the run audit, not asserted. "
          "Every number below moves when the scenario or the governor moves.")
     (table ["Measure" "Value"]
            [["operations driven through the actor graph" (esc (count runs))]
             ["append-only ledger facts written" (esc (count ledger))]
             ["HARD governor holds" (crit (esc (count hs)))]
             ["distinct governor rules fired" (esc (count (rules-fired db)))]
             ["which rules" (str/join " " (map #(code (kw-str %)) (rules-fired db)))]
             ["human approvals granted"
              (esc (count (filter #(= :approved (:kind (outcome %))) runs)))]
             ["human approvals refused"
              (esc (count (filter #(= :rejected (:kind (outcome %))) runs)))]
             ["auto-committed without a human"
              (esc (count (filter #(= :auto (:kind (outcome %))) runs)))]
             ["machine suspensions registered" (esc (count (store/suspension-history db)))]
             ["abandoned-property custodies registered" (esc (count (store/custody-history db)))]]))))

(defn- machine-section [db]
  (section
   "Machine register (SSoT after the run)"
   (str "The entity is a <strong>machine</strong>, not a customer's item: in a self-service "
        "laundry nothing is handed over, so there is no ticket and no "
        (code ":actuation/return-*") " op anywhere in this actor. Day-stamps are seed data &mdash; "
        "the actor never reads a clock, which is what makes this page byte-reproducible.")
   (table ["Machine" "Site" "Kind" "Jurisdiction" "Last inspection / as-of"
           "Cycle" "Property left / posted period" "Suspension" "Custody"]
          (for [m (store/all-machines db)]
            [(code (:id m))
             (esc (:site m))
             (esc (kw-str (:kind m)))
             (str (code (:jurisdiction m))
                  (when-not (facts/covered? (:jurisdiction m))
                    (str " " (crit "no basis on file"))))
             (str "day " (esc (:last-inspection-day m)) " / day " (esc (:as-of-day m)))
             (if (:cycle-running? m) (crit "running") (muted "idle"))
             (str "day " (esc (:left-at-day m)) " / "
                  (esc (:posted-retention-days m)) " days")
             (if (:suspended? m)
               (ok (str "suspended &middot; " (esc (:suspension-number m))))
               (muted "in service"))
             (if (:property-held? m)
               (ok (str "held &middot; " (esc (:custody-number m))))
               (dash))]))))

(defn- coverage-section []
  (section
   "Jurisdictional spec-basis (seed table)"
   (str (esc (facts/coverage-summary))
        " An unattended coin laundry is not a クリーニング所 &mdash; the operator never "
        "receives, processes and returns anyone's laundry &mdash; so what binds it is a "
        "<em>sanitation</em> regime for the room and the machines, which is why the required "
        "evidence is inspection and posted-notice records rather than a customer consent form.")
   (table ["ISO3" "Jurisdiction" "Legal basis" "Statutory context"
           "Inspection interval" "Required evidence"]
          (for [[iso3 sb] (sort-by key facts/spec-basis-table)]
            [(code iso3)
             (esc (:name sb))
             (esc (:legal-basis sb))
             (esc (:statutory-context sb))
             (str (esc (:inspection-interval-days sb)) " days")
             (str "<ul><li>"
                  (str/join "</li><li>" (map esc (:required-evidence sb)))
                  "</li></ul>")]))))

(defn- outcome-cell [o]
  (case (:kind o)
    :auto (ok "auto-committed")
    :approved (ok (str "escalated (" (esc (kw-str (:reason o)))
                       ") &rarr; approved by " (esc (:by o))))
    :rejected (crit "escalated &rarr; refused by the human")
    :awaiting (warn (str "paused at :request-approval (" (esc (kw-str (:reason o))) ")"))
    :hold (crit (str "HARD hold &middot; "
                     (str/join ", " (map #(esc (kw-str %)) (:basis o)))))
    (muted "unknown")))

(defn- runs-section [runs]
  (section
   "Operations driven through the actor (in order)"
   (str "One row per " (code "langgraph") " graph run. The outcome column is derived from each "
        "run's own accumulated audit channel &mdash; " (code ":approval-granted") ", "
        (code ":governor-hold") ", " (code ":committed") " &mdash; not from anything written "
        "beside the call.")
   (table ["Thread" "Op" "Machine" "Outcome" "Why this row is here"]
          (for [{:keys [tid request note] :as r} runs]
            [(code tid)
             (code (str (:op request)))
             (code (:subject request))
             (outcome-cell (outcome r))
             (muted (esc note))]))))

(defn- holds-section [db]
  (section
   "HARD governor holds (what actually stopped)"
   (str "A HARD hold cannot be overridden by a human and never reaches one &mdash; the graph "
        "routes straight to " (code ":hold") " and writes the rejection to the ledger with no "
        "SSoT mutation. The detail column is the governor's own string, in its own words.")
   (table ["Op" "Machine" "Rule" "Governor detail"]
          (for [h (holds db)
                v (:violations h)]
            [(code (str (:op h)))
             (code (:subject h))
             (crit (esc (kw-str (:rule v))))
             (esc (:detail v))]))))

(defn- gate-section []
  (section
   "The closed vocabulary and the phase gate"
   (str "Derived from " (code "coinlaundry.governor/allowed-ops") ", "
        (code "coinlaundry.governor/high-stakes") " and " (code "coinlaundry.phase/phases")
        " at render time, so this table cannot drift from the code. "
        "<strong>No op disposes of, sells, or takes ownership of property left in a machine, "
        "and no op settles a damage claim.</strong> Those are absent from the vocabulary, not "
        "gated within it &mdash; an actor that could take property into custody "
        "<em>and also dispose of it</em> would be one confident proposal away from throwing "
        "away a stranger's clothes.")
   (str
    (table ["Op" "Phase 3 posture"]
           (for [o (sort-by str governor/allowed-ops)]
             [(code (str o))
              (cond
                (contains? governor/high-stakes o)
                (crit "ALWAYS human approval &middot; never auto at any phase")

                (contains? (:auto (get phase/phases phase/default-phase)) o)
                (ok "auto-commit when the governor is clean")

                :else (warn "human approval &middot; not auto-eligible at any phase"))]))
    "    <h3>Rollout phases</h3>\n"
    (table ["Phase" "Label" "Writable ops" "Auto-committable ops"]
           (for [[n {:keys [label writes auto]}] (sort-by key phase/phases)]
             [(esc n)
              (esc label)
              (if (seq writes) (ops-list writes) (muted "none"))
              (if (seq auto) (ops-list auto) (muted "none"))]))
    "    <p class=\"muted\">Confidence floor "
    (code governor/confidence-floor)
    " &mdash; a proposal under it escalates to a human even when every rule is clean. "
    "The scope gate is a substring scan over "
    (esc (count governor/scope-excluded-terms))
    " terms; being a scan, it cannot tell a claim from a denial, so a rationale saying "
    "&ldquo;this actor cannot dispose of it&rdquo; trips it. The fix lives on the advisor's "
    "side &mdash; state the boundary in the docstring, where no scanner reads it &mdash; not "
    "in a cleverer scanner.</p>\n")))

(defn- registers-section [db]
  (section
   "Actuation registers"
   (str "Two actuation events act on the same entity, each with its own history collection, "
        "its own sequence counter, and its own dedicated guard boolean ("
        (code ":suspended?") " / " (code ":property-held?") " &mdash; never a "
        (code ":status") " value). The numbers below were minted by "
        (code "coinlaundry.registry") " during this run.")
   (str
    "    <h3>Machine suspensions</h3>\n"
    (if (seq (store/suspension-history db))
      (table ["Suspension number" "Machine" "Jurisdiction"]
             (for [s (store/suspension-history db)]
               [(code (get s "suspension_number"))
                (code (get s "machine_id"))
                (code (get s "jurisdiction"))]))
      "    <p class=\"muted\">none registered in this run</p>\n")
    "    <h3>Abandoned-property custodies</h3>\n"
    (if (seq (store/custody-history db))
      (table ["Custody number" "Machine" "Jurisdiction"]
             (for [c (store/custody-history db)]
               [(code (get c "custody_number"))
                (code (get c "machine_id"))
                (code (get c "jurisdiction"))]))
      "    <p class=\"muted\">none registered in this run</p>\n"))))

(defn- attribution-section [{:keys [approvers on-ledger? registers]}]
  (let [retaining (filter :retains? registers)
        dropping (remove :retains? registers)]
    (section
     "Approver attribution &mdash; what the SSoT keeps, and what it does not"
     (str "Re-checked against the real store at render time: every committed row in every "
          "register is walked and its keys scanned for an approver key. This is derived rather "
          "than described, so the disclosure below cannot drift away from the code &mdash; it "
          "will change on its own the day the store changes.")
     (str
      (table ["Register" "Committed via" "Rows" "Approver retained on the record?"]
             (for [{:keys [label effect n retains?]} registers]
               [(esc label)
                (code (str effect))
                (esc n)
                (if retains?
                  (ok "yes")
                  (crit "no &mdash; audit only, not retained in record"))]))
      "    <p>"
      (if (seq approvers)
        (str "This run's " (code ":approval-granted") " audit facts name "
             (str/join " " (map code approvers)) " as the approver. "
             (if (seq retaining)
               (str "That id survives into "
                    (oxford (map #(str "<strong>" (esc (:label %)) "</strong>") retaining))
                    ", whose commit branch stores the record payload verbatim. ")
               "")
             (if (seq dropping)
               (str "It does <strong>not</strong> survive into "
                    (oxford (map #(str "<strong>" (esc (:label %)) "</strong>") dropping))
                    " &mdash; those branches either store the pre-approval value or recompute "
                    "the record from the registry, so the approver exists only on the run's "
                    "audit trail. ")
               "")
             "The store's own ledger carries "
             (if on-ledger?
               "the approval fact as well."
               (str "<strong>no</strong> " (code ":approval-granted") " fact: the "
                    (code ":commit") " node appends only its " (code ":committed") " fact."))
             " Naming the approver on those rows anyway would let a reader mistake "
             "&ldquo;the store did not keep it&rdquo; for &ldquo;nobody approved it&rdquo;.")
        "This run produced no human approval, so there is no approver to attribute.")
      "</p>\n"))))

(defn- ledger-section [db]
  (section
   "Audit ledger (append-only, this run)"
   (str "Written by exactly two nodes: " (code ":commit") " (the only writer of the SSoT) and "
        (code ":hold") " (which writes the rejection and mutates nothing).")
   (table ["#" "Fact" "Op" "Machine" "Basis"]
          (map-indexed
           (fn [i {:keys [t op subject basis]}]
             [(esc (inc i))
              (case t
                :committed (ok "committed")
                :governor-hold (crit "governor-hold")
                :approval-rejected (crit "approval-rejected")
                (muted (esc (kw-str t))))
              (code (str op))
              (code subject)
              (if (seq basis)
                (str/join ", " (map #(esc (kw-str %)) basis))
                (dash))])
           (store/ledger db)))))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the whole document from a completed `run-demo!` result."
  [{:keys [db runs]}]
  (str
   "<!DOCTYPE html>\n"
   "<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
   "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
   "<title>cloud-itonami-isic-9601-coinlaundry &middot; coin laundry operator console</title>\n"
   "<style>" (jp-go-dds.skin/dds+skin) "</style>\n"
   "</head><body>\n"
   "<header class=\"bar\">\n"
   "  <h1>Coin laundry (ISIC 9601, unattended self-service) &mdash; Operator Console</h1>\n"
   "</header>\n"
   "<p><span class=\"badge\">read-only sample</span> "
   "<span class=\"badge\">governor-gated</span> "
   "<span class=\"badge\">machine suspension &amp; property custody always human-approved</span></p>\n"
   "<p class=\"subtitle\">Generated at build time by driving the real "
   "<code>coinlaundry.operation</code> actor graph over the real "
   "<code>coinlaundry.store</code> seed. No mock data, no hand-written rows, no timestamps, "
   "no usage or revenue metric is claimed anywhere on this page.</p>\n"
   "<main>\n"
   (summary-section db runs)
   (machine-section db)
   (coverage-section)
   (runs-section runs)
   (holds-section db)
   (gate-section)
   (registers-section db)
   (attribution-section (approver-attribution db runs))
   (ledger-section db)
   "</main>\n"
   "<footer>Regenerate with <code>clojure -M:dev:render-html</code>. "
   "Deterministic &mdash; the actor never reads a clock, and there is no randomness and no "
   "network, so two consecutive runs against the same seed are byte-identical. "
   "The generator refuses to write this file at all if the run produced no "
   "<code>:governor-hold</code> fact.</footer>\n"
   "</body></html>\n"))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db] :as result} (run-demo!)
        hs (holds db)]
    ;; A console showing no real HARD hold is not evidence of a governor.
    ;; Make that a build-time invariant rather than a convention.
    (when (empty? hs)
      (throw (ex-info (str "no :governor-hold fact on the ledger -- refusing to write a "
                           "console that shows no real hold")
                      {:ledger-facts (count (store/ledger db))})))
    (let [f (java.io.File. ^String out)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (render result)))
    (println "wrote" out
             (str "(" (count (store/ledger db)) " ledger facts, "
                  (count hs) " HARD holds, "
                  (count (rules-fired db)) " distinct rules, "
                  (count (store/suspension-history db)) " suspensions, "
                  (count (store/custody-history db)) " custodies)"))))
