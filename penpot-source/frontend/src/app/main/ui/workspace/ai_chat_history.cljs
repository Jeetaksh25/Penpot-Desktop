;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.ai-chat-history
  "P2.30 — Per-project AI chat-history browser.

  A persistent, per-file conversation log stored in browser
  `localStorage` (key `ovion-ai-chats:<file-id>`). The closed backend's
  per-file memory (`<app-data>/ai-memory/<file-id>.json`) is append-only
  last-N-turns with a single `llm_clear_memory` command and exposes no
  list/load API to the frontend (lib.rs / llm.rs are not editable here),
  so a frontend-only store is the maximum unblocked implementation. It
  is fully adequate for a session list + resume UI.

  Session shape:
    {:id         <string>          ; unique session id
     :title      <string>          ; first user prompt, truncated
     :created-at <int ms>         ; creation epoch millis
     :messages   [{:role <user|assistant>
                   :content <string>
                   :timestamp <int ms>}]}

  This namespace provides:
    - `load-sessions` / `save-sessions` — localStorage IO (defensive).
    - `new-session` — build an empty session with a fresh id.
    - `append-message` — add a message + refresh the title.
    - `chat-history-popover` — the list / New chat / Resume / Delete UI,
      styled to match the AI bar's reference visual world (white surface,
      coral inset ring, Helvetica Now Display, Lucide glyphs). Used by
      `ai_bar.cljs`, which owns the session state."
  (:require
   [cuerdas.core :as str]
   [app.common.data.macros :as dm]
   [app.main.ui.workspace.ai-design :as ad]
   [app.main.ui.workspace.ai-motion :as aim]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

;; ── localStorage IO ───────────────────────────────────────────────────────────

(defn- storage-key
  [file-id]
  (dm/str "ovion-ai-chats:" (or file-id "default")))

(defn load-sessions
  "Read the persisted session vector for `file-id` from localStorage.
  Returns [] on any failure / missing key (defensive — never throws)."
  [file-id]
  (try
    (let [raw (.getItem js/localStorage (storage-key file-id))]
      (if (str/empty? raw)
        []
        (let [v (js/JSON.parse raw)]
          (if (array? v) (js->clj v :keywordize-keys true) []))))
    (catch :default _ [])))

(defn save-sessions
  "Persist the session vector for `file-id` to localStorage. Defensive —
  swallows quota / serialization errors so a chat-log write never breaks
  the AI bar."
  [file-id sessions]
  (try
    (.setItem js/localStorage
              (storage-key file-id)
              (js/JSON.stringify (clj->js sessions)))
    (catch :default _ nil)))

;; ── Session helpers ──────────────────────────────────────────────────────────

(defn new-session
  "Build a fresh empty session."
  []
  {:id         (str (js/crypto.randomUUID))
   :title      (tr "workspace.ai.bar.chat.new-chat")
   :created-at (js/Date.now)
   :messages   []})

(defn- truncate
  [s n]
  (if (> (count s) n) (dm/str (subs s 0 n) "…") s))

(defn append-message
  "Return a new session with `message` ({:role :content :timestamp})
  appended. When `:role` is :user and the session still has the default
  title, the title is set to the (truncated) message content so the
  history list shows a meaningful label."
  [session message]
  (let [msg (assoc message :timestamp (js/Date.now))
        msgs (conj (vec (:messages session [])) msg)
        title (if (and (= (:role msg) "user")
                       (or (str/empty? (:title session))
                           (= (:title session)
                              (tr "workspace.ai.bar.chat.new-chat"))))
                (truncate (str/trim (:content msg "")) 48)
                (:title session))]
    (assoc session :messages msgs :title title)))

;; ── Injected CSS (scoped under .aich-root) ────────────────────────────────────

(def ^:private chat-css
  "
.aich-pop {
  position: absolute; bottom: calc(100% + 10px); left: 0; z-index: 71;
  min-width: 280px; max-width: 320px; background: var(--ai-white);
  border-radius: var(--ai-radius-md);
  box-shadow: var(--ai-shadow-soft), inset 0 0 0 2px var(--ai-coral);
  padding: 8px; opacity: 0;
  display: flex; flex-direction: column; gap: 6px;
  max-height: 360px;
}
.aich-head { display: flex; align-items: center; justify-content: space-between;
  padding: 2px 4px 6px; }
.aich-title { font-size: 12px; font-weight: 700; color: var(--ai-ink);
  font-family: var(--ai-font); }
.aich-list { display: flex; flex-direction: column; gap: 2px; overflow: auto;
  flex: 1; }
.aich-empty { font-size: 12px; color: var(--ai-grey-2); font-family: var(--ai-font);
  padding: 14px 8px; text-align: center; }
.aich-item { display: flex; align-items: center; gap: 8px; padding: 8px 10px;
  border: none; cursor: pointer; background: transparent; text-align: left;
  border-radius: var(--ai-radius-sm);
  font-family: var(--ai-font); color: var(--ai-grey-2);
  transition: background var(--ai-dur-fast) var(--ai-ease-out),
              color var(--ai-dur-fast) var(--ai-ease-out); }
.aich-item:hover { background: var(--ai-coral-faint); color: var(--ai-ink); }
.aich-item.is-active { color: var(--ai-coral); background: var(--ai-coral-faint); }
.aich-item-main { flex: 1 1 auto; min-width: 0; display: flex; flex-direction: column;
  gap: 2px; }
.aich-item-title { font-size: 13px; font-weight: 600; white-space: nowrap;
  overflow: hidden; text-overflow: ellipsis; }
.aich-item-meta { font-size: 10.5px; color: var(--ai-grey-2); }
.aich-i { width: 15px; height: 15px; flex: none; }
.aich-i-x { width: 13px; height: 13px; flex: none; color: var(--ai-grey);
  transition: color var(--ai-dur-fast) var(--ai-ease-out); }
.aich-x-btn { border: none; background: none; cursor: pointer; padding: 2px;
  display: inline-flex; align-items: center; justify-content: center;
  border-radius: 4px; }
.aich-x-btn:hover .aich-i-x { color: #b3261e; }
.aich-new { display: inline-flex; align-items: center; justify-content: center; gap: 7px;
  height: 32px; padding: 0 12px; border: none; cursor: pointer;
  background: var(--ai-coral); color: var(--ai-white); border-radius: var(--ai-radius-sm);
  font-family: var(--ai-font); font-size: 12.5px; font-weight: 600;
  box-shadow: var(--ai-shadow-btn), var(--ai-inset-white);
  transition: background var(--ai-dur-fast) var(--ai-ease-out); }
.aich-new:hover { background: var(--ai-coral-press); }
.aich-new .aich-i { width: 14px; height: 14px; }
")

;; Lucide glyphs (stroke-width 2, currentColor).
(defn- li [body]
  [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
         :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
         :aria-hidden "true"} body])

(def ^:private lucide-history
  (li [[:path {:d "M3 12a9 9 0 1 0 3-6.7L3 8"}]
       [:path {:d "M3 3v5h5"}]
       [:path {:d "M12 7v5l3 3"}]]))

(def ^:private lucide-plus
  (li [[:path {:d "M5 12h14"}]
       [:path {:d "M12 5v14"}]]))

(def ^:private lucide-message
  (li [[:path {:d "M7.9 20A9 9 0 1 0 4 16.1L2 22Z"}]]))

(def ^:private lucide-x
  (li [[:path {:d "M18 6 6 18"}]
       [:path {:d "m6 6 12 12"}]]))

(defn- format-time
  "Compact relative timestamp for the history list."
  [ms]
  (let [now (js/Date.now)
        diff (- now (or ms 0))]
    (cond
      (< diff 60000)            (tr "workspace.ai.bar.chat.just-now")
      (< diff 3600000)          (tr "workspace.ai.bar.chat.min-ago"
                                  (Math.floor (/ diff 60000)))
      (< diff 86400000)         (tr "workspace.ai.bar.chat.hour-ago"
                                  (Math.floor (/ diff 3600000)))
      :else                     (let [d (js/Date. (or ms 0))]
                                  (.toLocaleDateString d)))))

;; ── The popover ───────────────────────────────────────────────────────────────

(mf/defc chat-history-popover
  "Render the chat-history list popover. Props:
    :sessions   vector of session maps
    :active-id  id of the currently active session (for highlight)
    :on-new     0-ary callback — start a fresh chat
    :on-resume  1-ary (session) — resume a past chat
    :on-delete  1-ary (session) — delete a session
    :pop-ref    ref for the popover element (for anime entrance)
    :on-close   0-ary — close the popover (backdrop click)"
  [{:keys [sessions active-id on-new on-resume on-delete pop-ref on-close]}]
  [:div.aich-root
   [:div {:style #js {"display" "contents"}}
    (ad/base-style-block)
    [:style {:dangerouslySetInnerHTML #js {:__html chat-css}}]]
   ;; Backdrop (click closes) — mirrors the AI bar screen-picker pattern.
   [:div.ai-screen-back {:on-click on-close}]
   [:div.aich-pop {:ref pop-ref}
    [:div.aich-head
     [:span.aich-title (tr "workspace.ai.bar.chat.title")]
     [:button.aich-new
      {:type "button" :on-click on-new
       :on-mouse-enter aim/hov-coral-in
       :on-mouse-leave aim/hov-coral-out
       :on-mouse-down aim/press-coral-in
       :on-mouse-up aim/press-coral-out}
      lucide-plus
      (tr "workspace.ai.bar.chat.new-chat")]]
    (if (empty? sessions)
      [:div.aich-empty (tr "workspace.ai.bar.chat.empty")]
      [:div.aich-list
       (for [s sessions]
         (let [sid (:id s)
               active? (= sid active-id)]
           [:div.aich-item
            {:key sid
             :class (when active? "is-active")
             :on-click #(on-resume s)
             :role "button"
             :tab-index "0"}
            [:span.aich-i lucide-message]
            [:span.aich-item-main
             [:span.aich-item-title (or (:title s) "—")]
             [:span.aich-item-meta
              (tr "workspace.ai.bar.chat.msg-count" (count (:messages s)))
              " · "
              (format-time (:created-at s))]]
            [:button.aich-x-btn
             {:type "button"
              :on-click (fn [e]
                          (.stopPropagation e)
                          (on-delete s))
              :title (tr "workspace.ai.bar.chat.delete")}
             [:svg.aich-i-x
              {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
               :stroke-width 2 :stroke-linecap "round"
               :stroke-linejoin "round" :aria-hidden "true"}
              [:path {:d "M18 6 6 18"}]
              [:path {:d "m6 6 12 12"}]]]]))])]])

;; Expose the history glyph so the AI bar can reuse it for its trigger button.
(def history-icon lucide-history)
