(ns coinlaundry.facts
  "Jurisdictional spec-basis table for **unattended, self-service** coin
  laundries.

  A jurisdiction NOT in this table has **no spec-basis, full stop** --
  the advisor reports that honestly (empty `:cites`) and the Coin
  Laundry Governor turns it into a HARD hold.

  ## What is actually regulated, and what is not

  ISIC 9601's `includes` names \"coin-operated laundry activities\"
  (measured from `cloud-itonami/org-un-isic`, 2026-08-08). But a coin
  laundry is **not** a クリーニング所: the operator does not receive,
  process and return anyone's laundry, so the 届出 regime that binds an
  attended dry cleaner does not bind it in the same way.

  What does bind it in JPN is the 厚生労働省 guideline for
  **コインオペレーションクリーニング営業施設** -- periodic cleaning and
  disinfection of the machines and the room, and a posted notice to
  users. That is a *sanitation* regime for an unattended facility, and
  it is why `:required-evidence` asks for inspection records and a
  posted notice rather than for a customer consent form.

  `:inspection-interval-days` is the number the overdue check
  recomputes against. It is data, not a constant in the governor."
  (:require [clojure.string :as str]))

(def spec-basis-table
  {"JPN" {:name "Japan"
          :legal-basis "コインオペレーションクリーニング営業施設における衛生等管理要領（厚生労働省通知）"
          :statutory-context "クリーニング業法（昭和25年法律第207号）— ただし本営業は クリーニング所 の届出規制とは別枠"
          :provenance "厚生労働省 生活衛生関係通知"
          :inspection-interval-days 30
          :required-evidence ["施設清掃・消毒記録 (sanitation-inspection-record)"
                              "利用者向け掲示記録 (posted-notice-record)"
                              "機器保守点検記録 (machine-maintenance-record)"
                              "残置物取扱記録 (abandoned-property-record)"]}
   "USA" {:name "United States"
          :legal-basis "State/municipal self-service laundry sanitation codes (varies)"
          :statutory-context "No single federal instrument; local health codes govern"
          :provenance "State health department codes"
          :inspection-interval-days 30
          :required-evidence ["Sanitation inspection record"
                              "Posted notice record"
                              "Machine maintenance record"
                              "Abandoned property record"]}
   "DEU" {:name "Germany"
          :legal-basis "Infektionsschutzgesetz (IfSG) SS 36 (Hygieneanforderungen)"
          :statutory-context "Landesrechtliche Hygieneverordnungen"
          :provenance "Bundesgesetzblatt IfSG"
          :inspection-interval-days 30
          :required-evidence ["Hygiene-Prufprotokoll (sanitation-inspection-record)"
                              "Aushang-Protokoll (posted-notice-record)"
                              "Wartungsprotokoll (machine-maintenance-record)"
                              "Fundsachen-Protokoll (abandoned-property-record)"]}})

(defn spec-basis [iso3] (get spec-basis-table (some-> iso3 str/upper-case)))
(defn covered? [iso3] (some? (spec-basis iso3)))

(defn coverage-summary []
  (str (count spec-basis-table)
       " jurisdictions seeded with an official spec-basis. "
       "A jurisdiction outside this set has NO basis on file and every "
       "proposal touching it is held."))

(defn required-evidence [iso3] (:required-evidence (spec-basis iso3) []))

(defn inspection-interval-days
  "The jurisdiction's maximum interval between sanitation inspections,
  or nil when the jurisdiction is not seeded. **nil means the overdue
  check cannot be computed**, which the governor treats as a reason to
  hold rather than as permission to skip."
  [iso3]
  (:inspection-interval-days (spec-basis iso3)))

(defn required-evidence-satisfied?
  "A missing spec-basis can NEVER be satisfied -- returns nil (falsey)."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (= (count required-evidence)
       (count (filter (set submitted) required-evidence)))))
