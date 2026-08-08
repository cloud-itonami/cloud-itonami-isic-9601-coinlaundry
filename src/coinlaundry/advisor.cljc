(ns coinlaundry.advisor
  "The **contained intelligence**: a CoinLaundryAdvisor that drafts
  proposals and nothing else. Every proposal is censored by
  `coinlaundry.governor` and gated by `coinlaundry.phase`.

  **The advisor reports missing basis honestly** (empty `:cites`, no
  confidence bump) rather than inventing a sanitation requirement.

  **The advisor never proposes disposing of property**, because there is
  no op for it. If a real LLM advisor describes disposal in prose, the
  governor's scope-exclusion check catches it on the way through."
  (:require [coinlaundry.facts :as facts]
            [coinlaundry.store :as store]))

(defprotocol Advisor
  (-advise [a store request] "Draft a proposal for this request."))

(defn trace [request proposal]
  {:t :advisor-proposed :op (:op request) :subject (:subject request)
   :summary (:summary proposal) :cites (:cites proposal)
   :confidence (:confidence proposal)})

(defn- propose-register
  "`:patch` arrives at the TOP level of the request."
  [_st {:keys [subject patch confidence]}]
  {:op :machine/register
   :summary (str subject " を機器台帳に登録")
   :rationale "登録の記録行為。機器の運転にも利用者の洗濯物にも触れない。"
   :cites (vec (keys patch))
   :effect :machine/upsert
   :value (merge {:id subject} patch)
   :confidence (or confidence 0.9)})

(defn- propose-sanitation-plan [st {:keys [subject]}]
  (let [m (store/machine st subject)
        iso3 (:jurisdiction m)
        sb (facts/spec-basis iso3)]
    (if-not sb
      {:op :sanitation-plan/verify
       :summary (str iso3 " の公式spec-basis(衛生等管理要領)が見つかりません")
       :rationale (str iso3 " は台帳に無い法域。無人店舗の衛生基準を推測で作らない。")
       :cites []
       :effect :sanitation-plan/set
       :value {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :confidence 0.2}
      {:op :sanitation-plan/verify
       :summary (str subject " の衛生管理計画を " iso3 " 基準で検証")
       :rationale (str (:legal-basis sb) " に基づく必要書類の充足確認。")
       :cites [(:legal-basis sb) (:provenance sb)]
       :effect :sanitation-plan/set
       :value {:jurisdiction iso3
               :checklist (facts/required-evidence iso3)
               :spec-basis (:provenance sb)}
       :confidence 0.88})))

(defn- propose-inspection-screen [st {:keys [subject]}]
  (let [m (store/machine st subject)]
    {:op :inspection/screen
     :summary (str subject " の清掃・消毒点検の現況を確認（最終点検 "
                   (:last-inspection-day m) " / 基準日 " (:as-of-day m) "）")
     :rationale "点検の現況は台帳の日付から再計算する。提案者の自己申告では判定しない。"
     :cites [:sanitation-inspection-check]
     :effect :inspection-screening/set
     :value {:machine-id subject
             :last-inspection-day (:last-inspection-day m)
             :as-of-day (:as-of-day m)}
     :confidence 0.9}))

(defn- propose-suspend [st {:keys [subject]}]
  (let [m (store/machine st subject)
        sb (facts/spec-basis (:jurisdiction m))]
    {:op :actuation/suspend-machine
     :summary (str subject " を停止（点検/故障対応）")
     :rationale "機器の停止。利用者が使用中の可能性があるため人の承認を要する。"
     :cites (if sb [(:legal-basis sb) subject] [])
     :effect :machine/mark-suspended
     :value {:machine-id subject :spec-basis (:provenance sb)}
     :confidence 0.85}))

(defn- propose-hold-property [st {:keys [subject]}]
  (let [m (store/machine st subject)
        sb (facts/spec-basis (:jurisdiction m))]
    {:op :actuation/hold-abandoned-property
     :summary (str subject " の残置物を保管記録に移す")
     ;; NOTE: this rationale deliberately does NOT name the excluded acts,
     ;; even to deny them. The scope gate is a substring scan, so a
     ;; sentence saying "this actor cannot 売却 or 処分" contains the very
     ;; terms it disclaims and trips the gate on a legitimate proposal.
     ;; Measured 2026-08-08: the first draft said exactly that and the
     ;; demo held every hold-property run with :scope-excluded.
     ;; The boundary belongs in the ns docstring and the README, where no
     ;; scanner reads it -- not in text the governor inspects.
     :rationale (str "掲示保管期間の経過後に残置物を保管記録へ移す。"
                     "占有のみが移り、権利関係は変わらない。人の承認を要する。")
     :cites (if sb [(:legal-basis sb) subject] [])
     :effect :machine/mark-property-held
     :value {:machine-id subject :spec-basis (:provenance sb)}
     :confidence 0.85}))

(defn infer [st {:keys [op] :as request}]
  (case op
    :machine/register                    (propose-register st request)
    :sanitation-plan/verify              (propose-sanitation-plan st request)
    :inspection/screen                   (propose-inspection-screen st request)
    :actuation/suspend-machine           (propose-suspend st request)
    :actuation/hold-abandoned-property   (propose-hold-property st request)
    {:op op :summary "未対応の操作" :rationale (str op)
     :cites [] :effect :noop :value {} :confidence 0.0}))

(defn mock-advisor []
  (reify Advisor (-advise [_ st request] (infer st request))))
