;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.ai-checklist
  "AI Design Checklist (ALL_APPS_PARITY P2.01) + Focus-area predictor (P2.19).

  P2.01 — `generate-checklist` asks the model for a tailored UX/content
  design-verification checklist for the current screen, parses the model's
  markdown into a vector of tickable `{:id :label :done?}` items, and
  persists it on FILE-DATA plugin-data (namespace `:ovion`, key
  `\"ai-checklist\"`) so it survives save/reload and is undo/redo-safe.
  `toggle-checklist-item` flips one item's `:done?` and re-persists (one
  undo transaction). `clear-checklist` removes the slot.

  P2.19 — `predict-focus` asks the model to predict the next canvas area
  the user should focus on, given the current selection + page context,
  and returns `{:shape-id <uuid-str> :rationale <string>}`. The latest
  prediction is persisted on `:ovion \"ai-focus\"` (optional, survives
  reload) and also surfaced to the UI via `set-ai-focus` (ephemeral
  :workspace-local slot, mirrors `set-ai-review`). `goto-focus` selects +
  zoomes to the predicted shape via the existing `select-shape` /
  `zoom-to-selected-shape` events.

  LLM invoke path — REUSED, no new Rust command. Both calls go through
  `ai/invoke-generate-spec-doc` (the existing `llm_generate_spec_doc`
  Tauri command), which is non-streaming and returns `{:markdown :html}`.
  The instruction is carried by the `scope` string and the page/selection
  context by the `scene` JSON value; the markdown response is parsed on
  the CLJS side into the checklist / focus shapes. Mirrors
  `ai_gen.cljs ::review-design` / `::generate-spec-doc` (stale-id guard,
  set-ai-busy, st/emit! in handlers). A LOCAL stale-id atom is kept here
  because `ai_gen.cljs`'s `gen-id` is private; the semantics are
  identical (drop a late-arriving result when a newer call has fired).

  Storage:
    file-data :plugin-data :ovion \"ai-checklist\" -> EDN string of the
      checklist vector `[{:id :label :done?}]`.
    file-data :plugin-data :ovion \"ai-focus\" -> EDN string of the
      latest focus map `{:shape-id :rationale}`.

  Plugin-data persistence mirrors `data/workspace/prompt_library.cljs`
  (3-arg `pcb/set-plugin-data`, one undo transaction, object-type :file).
  Commits from async LLM handlers re-enter the store via dedicated
  `commit-checklist-event` / `commit-focus-event` WatchEvents so the
  changeset is built against FRESH state (the `state` captured in a
  promise handler would be stale)."
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.ai-gen :as ai]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.zoom :as dwz]
   [app.main.store :as st]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cljs.reader :as reader]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]
   [promesa.core :as p]))

;; --- Stale-result guard -----------------------------------------------------

;; Monotonic call id used to drop stale LLM results (a cancelled or
;; superseded call still resolves eventually). Each generate-checklist /
;; predict-focus call captures a fresh id; a late-arriving result whose
;; captured id no longer matches `@call-id` is dropped. Mirrors
;; ai_gen.cljs's private `gen-id` atom — kept local here because that
;; one is namespace-private.
(def ^:private call-id (atom 0))

(defn- bump-call-id [] (swap! call-id inc))
(defn- call-id-current [] @call-id)

(defn- err->str
  "Coerce a thrown error/value into a user-facing string. Mirrors the
  private `err->str` in ai_gen.cljs."
  [err]
  (cond
    (string? err)              err
    (some-> err .-message)     (.-message err)
    :else                      (tr "workspace.ai.bar.error-generic")))

;; --- Plugin-data slot constants --------------------------------------------

(def checklist-namespace
  "Plugin-data namespace keyword under which the AI checklist is stored
  on the file. Schema:plugin-data keys namespaces are keywords."
  :ovion)

(def checklist-key
  "Plugin-data key (string) under `checklist-namespace` for the checklist
  vector."
  "ai-checklist")

(def focus-key
  "Plugin-data key (string) under `:ovion` for the latest focus-area
  prediction map."
  "ai-focus")

;; --- Read helpers -----------------------------------------------------------

(defn read-checklist
  "Parse the file-data ai-checklist slot back into a vector of
  `{:id :label :done?}` maps. Returns [] when the slot is absent /
  unparsable. `file-data` is the file-data map (NOT the whole state)."
  [file-data]
  (let [raw (dm/get-in file-data [:plugin-data checklist-namespace checklist-key])]
    (if (or (nil? raw) (empty? raw))
      []
      (try
        (let [v (reader/read-string raw)]
          (if (vector? v) v []))
        (catch :default _
          [])))))

(defn read-focus
  "Parse the file-data ai-focus slot back into a
  `{:shape-id :rationale}` map, or nil. `file-data` is the file-data map."
  [file-data]
  (let [raw (dm/get-in file-data [:plugin-data checklist-namespace focus-key])]
    (if (or (nil? raw) (empty? raw))
      nil
      (try
        (let [v (reader/read-string raw)]
          (if (map? v) v nil))
        (catch :default _
          nil)))))

;; --- Context builder --------------------------------------------------------

(defn- page-name
  "Current page name, or nil."
  [state]
  (let [page (dsh/lookup-page state)]
    (:name page)))

(defn- shape-summary
  "One compact map for a shape, safe for JSON serialization to the model."
  [id shape]
  (when (and (map? shape) (uuid? id))
    {:id     (str id)
     :name   (:name shape "")
     :type   (name (:type shape :rect))
     :x      (:x shape 0)
     :y      (:y shape 0)
     :width  (:width shape 0)
     :height (:height shape 0)}))

(defn- build-context
  "Build the JSON-serializable context map sent to the model as the
  `scene` param. Includes the page name, the current selection summary,
  and (for focus prediction) the list of candidate shapes with ids so
  the model can name one. Returns a plain CLJS map."
  ([state] (build-context state false))
  ([state include-shapes?]
   (let [objects  (dsh/lookup-page-objects state)
         selected (dsh/lookup-selected state)
         sel-sum  (when (seq selected)
                    (into []
                          (keep (fn [id]
                                  (shape-summary id (get objects id))))
                          selected))
         ctx {:page-name (or (page-name state) "")
              :selection sel-sum}]
     (if include-shapes?
       (let [top (into []
                       (keep (fn [[id shape]]
                               (shape-summary id shape)))
                       objects)]
         (assoc ctx :shapes top))
       ctx))))

;; --- Markdown parsing -------------------------------------------------------

(defn- parse-checklist-markdown
  "Parse the model's markdown into a vector of `{:id :label :done?}`.
  Recognizes GitHub task-list lines `- [ ] <label>` and `- [x] <label>`
  (case-insensitive x). Falls back to plain `- <label>` bullets when no
  task lines are found, so a model that ignores the task syntax still
  yields a usable checklist. Stable ids `\"cl-<idx>\"` are assigned by
  position. Blank labels are dropped."
  [markdown]
  (let [lines   (.. (str markdown) (split "\n"))
        items   (volatile! [])
        task-re #"^\s*[-*]\s+\[([ xX])\]\s+(.*)$"
        bullet-re #"^\s*[-*]\s+(.*)$"]
    (doseq [raw lines]
      (let [line (str/trim raw)]
        (when (not (str/empty? line))
          (let [tm (.match line task-re)]
            (if (nil? tm)
              (let [bm (.match line bullet-re)]
                (when (some? bm)
                  (let [label (str/trim (aget bm 1))]
                    (when (not (str/empty? label))
                      (vswap! items conj
                              {:id (str "cl-" (count @items))
                               :label label
                               :done? false})))))
              (let [mark (str/lower (aget tm 1))
                    label (str/trim (aget tm 2))]
                (when (not (str/empty? label))
                  (vswap! items conj
                          {:id (str "cl-" (count @items))
                           :label label
                           :done? (= mark "x")}))))))))
    (vec @items)))

(defn- parse-focus-markdown
  "Parse the model's markdown for a `FOCUS: <shape-id> | <rationale>`
  line. Returns `{:shape-id <string> :rationale <string>}` or nil. The
  shape-id string is validated as a uuid by the caller before selection."
  [markdown]
  (let [lines (.. (str markdown) (split "\n"))]
    (loop [ls lines]
      (if (empty? ls)
        nil
        (let [line (str/trim (first ls))]
          (if (str/starts-with? (str/lower line) "focus:")
            (let [rest (str/trim (.substring line (+ (.indexOf line ":") 1)))
                  bar  (.indexOf rest "|")]
              (if (pos? bar)
                {:shape-id  (str/trim (.substring rest 0 bar))
                 :rationale (str/trim (.substring rest (inc bar)))}
                {:shape-id (str/trim rest) :rationale ""}))
            (recur (rest ls))))))))

;; --- Prompt scopes ----------------------------------------------------------

(def ^:private checklist-scope
  "Instruction carried by the `scope` param of llm_generate_spec_doc.
  The system prompt forces a `{:markdown :html}` response, so the
  checklist is emitted as markdown task-list lines inside `markdown`."
  "Generate a UX/content design-verification checklist for the current screen. Output 6 to 10 items as markdown task-list lines, each starting with '- [ ] ' followed by a short actionable label (max 12 words). Cover: color contrast & palette consistency, typography hierarchy, spacing & alignment, content clarity, functional states (hover/error/empty), and accessibility. Put the checklist in the markdown field; leave html empty.")

(def ^:private focus-scope
  "Instruction carried by the `scope` param of llm_generate_spec_doc for
  focus-area prediction. The model names one shape id from the scene."
  "Predict the single next area of the canvas the user should focus on, given the current selection and the page shapes. Choose ONE shape id from the `shapes` list in the scene. Output exactly one markdown line in the format: FOCUS: <shape-id> | <one-line rationale of why this area deserves attention next>. Put that line in the markdown field; leave html empty. Do not output any other content.")

;; --- Commit helpers (pure; build against the passed state) -------------------

(defn- commit-checklist
  "Build and commit a changeset that writes `new-items` to the file's
  ai-checklist slot, inside one undo transaction. Returns an rx stream
  of potok events. Mirrors prompt_library.cljs's `commit-user-presets`."
  [it state new-items]
  (let [file-id   (:current-file-id state)
        file-data (dsh/lookup-file-data state file-id)]
    (if (nil? file-data)
      (rx/empty)
      (let [undo-id (js/Symbol)
            value   (if (or (nil? new-items) (empty? new-items))
                      nil
                      (pr-str new-items))
            changes (-> (pcb/empty-changes it)
                        (pcb/with-file-data file-data)
                        (pcb/set-plugin-data checklist-namespace
                                              checklist-key
                                              value))]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes changes)
               (dwu/commit-undo-transaction undo-id))))))

(defn- commit-focus
  "Build and commit a changeset that writes `new-focus` to the file's
  ai-focus slot, inside one undo transaction. nil clears the slot."
  [it state new-focus]
  (let [file-id   (:current-file-id state)
        file-data (dsh/lookup-file-data state file-id)]
    (if (nil? file-data)
      (rx/empty)
      (let [undo-id (js/Symbol)
            value   (when new-focus (pr-str new-focus))
            changes (-> (pcb/empty-changes it)
                        (pcb/with-file-data file-data)
                        (pcb/set-plugin-data checklist-namespace
                                              focus-key
                                              value))]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes changes)
               (dwu/commit-undo-transaction undo-id))))))

;; --- Commit events (re-enter the store from async handlers w/ FRESH state) ---

(defn commit-checklist-event
  "WatchEvent that persists `new-items` to the ai-checklist slot using
  FRESH store state. Emitted from the generate-checklist promise handler
  so the changeset is not built against a stale `state` snapshot."
  [new-items]
  (ptk/reify ::commit-checklist-event
    ptk/WatchEvent
    (watch [it state _]
      (commit-checklist it state new-items))))

(defn commit-focus-event
  "WatchEvent that persists `new-focus` to the ai-focus slot using FRESH
  store state. Emitted from the predict-focus promise handler."
  [new-focus]
  (ptk/reify ::commit-focus-event
    ptk/WatchEvent
    (watch [it state _]
      (commit-focus it state new-focus))))

;; --- Ephemeral focus result slot (workspace-local) --------------------------

(defn set-ai-focus
  "Store the focus-area prediction result in :workspace-local so the AI
  bar can render it. `payload` is `{:shape-id :rationale}` or nil.
  NON-mutating — never touches the canvas. Mirrors `ai/set-ai-review`."
  [payload]
  (ptk/reify ::set-ai-focus
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :ai-focus] payload))))

(defn clear-ai-focus
  "Clear the ephemeral focus prediction slot."
  []
  (ptk/reify ::clear-ai-focus
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :ai-focus] nil))))

;; --- Events -----------------------------------------------------------------

(defn generate-checklist
  "Ask the model for a design-verification checklist for the current
  screen, parse the markdown into tickable items, and persist them on
  file-level plugin-data `:ovion \"ai-checklist\"` (one undo transaction
  covers the persist). Reuses `llm_generate_spec_doc` (non-streaming)."
  []
  (ptk/reify ::generate-checklist
    ptk/WatchEvent
    (watch [_ state _]
      (let [my-id   (bump-call-id)
            ctx     (build-context state false)
            handle
            (fn [result]
              (when (= my-id (call-id-current))
                (let [payload (js->clj result :keywordize-keys true)
                      md      (:markdown payload)
                      items   (parse-checklist-markdown md)]
                  (if (empty? items)
                    (st/emit! (ai/set-ai-busy false)
                              (ai/set-ai-error
                               (tr "workspace.ai.bar.checklist-empty")))
                    (st/emit! (ai/set-ai-busy false)
                              (ai/set-ai-error nil)
                              (commit-checklist-event items))))))
            handle-err
            (fn [err]
              (when (= my-id (call-id-current))
                (st/emit! (ai/set-ai-busy false)
                          (ai/set-ai-error (err->str err)))))]
        (-> (ai/invoke-generate-spec-doc checklist-scope ctx)
            (p/then handle)
            (p/catch handle-err))
        (rx/of (ai/set-ai-busy true))))))

(defn toggle-checklist-item
  "Flip the `:done?` of the checklist item with `id` and re-persist the
  checklist. One undo transaction. No-op when the item is not found."
  [id]
  (ptk/reify ::toggle-checklist-item
    ptk/WatchEvent
    (watch [it state _]
      (let [file-id   (:current-file-id state)
            file-data (dsh/lookup-file-data state file-id)
            existing  (read-checklist file-data)
            new-vec   (mapv (fn [item]
                              (if (= (:id item) id)
                                (update item :done? not)
                                item))
                            existing)]
        (if (= existing new-vec)
          (rx/empty)
          (commit-checklist it state new-vec))))))

(defn clear-checklist
  "Remove the ai-checklist slot from file-level plugin-data. One undo
  transaction."
  []
  (ptk/reify ::clear-checklist
    ptk/WatchEvent
    (watch [it state _]
      (commit-checklist it state nil))))

(defn predict-focus
  "Ask the model to predict the next focus area given the current
  selection + page context. Persists the result on `:ovion \"ai-focus\"`
  and surfaces it via `set-ai-focus`. Reuses `llm_generate_spec_doc`
  (non-streaming). The `Go to` action is emitted separately by the UI
  bar via `dws/select-shape` + `dwz/zoom-to-selected-shape`."
  []
  (ptk/reify ::predict-focus
    ptk/WatchEvent
    (watch [_ state _]
      (let [my-id   (bump-call-id)
            ctx     (build-context state true)
            handle
            (fn [result]
              (when (= my-id (call-id-current))
                (let [payload (js->clj result :keywordize-keys true)
                      md      (:markdown payload)
                      focus   (parse-focus-markdown md)]
                  (if (nil? focus)
                    (st/emit! (ai/set-ai-busy false)
                              (ai/set-ai-error
                               (tr "workspace.ai.bar.focus-empty")))
                    (st/emit! (ai/set-ai-busy false)
                              (ai/set-ai-error nil)
                              (set-ai-focus focus)
                              (commit-focus-event focus))))))
            handle-err
            (fn [err]
              (when (= my-id (call-id-current))
                (st/emit! (ai/set-ai-busy false)
                          (ai/set-ai-error (err->str err)))))]
        (-> (ai/invoke-generate-spec-doc focus-scope ctx)
            (p/then handle)
            (p/catch handle-err))
        (rx/of (ai/set-ai-busy true))))))

(defn goto-focus
  "Select and zoom to the predicted shape. `shape-id-str` is the uuid
  string returned by the model; it is parsed to a uuid and passed to the
  existing `select-shape` + `zoom-to-selected-shape` events. If the id
  is not a valid uuid / not on the current page, emits an ai-error
  instead. Non-mutating beyond the selection/viewport."
  [shape-id-str]
  (ptk/reify ::goto-focus
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (dsh/lookup-page-objects state)]
        (try
          (let [id (uuid/uuid shape-id-str)]
            (if (contains? objects id)
              (rx/of (dws/select-shape id)
                     (dwz/zoom-to-selected-shape))
              (rx/of (ai/set-ai-error
                      (tr "workspace.ai.bar.focus-not-found")))))
          (catch :default _
            (rx/of (ai/set-ai-error
                    (tr "workspace.ai.bar.focus-not-found")))))))))