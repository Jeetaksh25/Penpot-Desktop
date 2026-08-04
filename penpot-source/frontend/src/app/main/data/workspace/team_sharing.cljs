;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.team-sharing
  "P2.37 — Team sharing (ALL_APPS_PARITY). The feasible half of the gap
  under no-build + no-live-cloud:

    (a) On-screen comments — a comment is
        {:id <uuid-str> :author <string> :body <string>
         :x <px> :y <px> :resolved? <bool>}
        Shape-anchored comments are stored on the SHAPE via
        `:ovion \"comments\"` = pr-str `[<comment> ...]` (6-arg
        set-plugin-data). Canvas-floating (page-level) comments are stored
        on the PAGE object via `:ovion \"page-comments\"` = pr-str
        `[<comment> ...]` (page-target set-plugin-data, mirrors
        plugin_registry.cljs).

    (b) Slack/Teams/Jira/Confluence webhook integrations —
        `post-comment-to-webhook` builds a provider-shaped JSON payload and
        invokes the Rust `post_webhook` Tauri command (see
        `src-tauri/src/team_sharing.rs`). The webhook URL + provider are held
        in browser localStorage (`ovion.webhook-url` /
        `ovion.webhook-provider`), key-gated like the Pexels key in
        `stock_assets.cljs`. Sentinels `webhook-url-missing` /
        `webhook-post-failed` mirror `pexels-key-missing`.

    (c) Share-link generation — `generate-share-link` mints
        `https://ovion.app/share/<base64-url-safe-file-id>` (placeholder
        scheme — no deep-link is registered in tauri.conf.json). The menu
        renders a QR via the api.qrserver.com image service + a copy button.

  App-level state slot `:comments-mode?` (default false) +
  `toggle-comments-mode` UpdateEvent + `comments-mode-ref` (okulary derived
  ref over `:comments-mode?` in `st/state`, defined HERE — mirrors
  `localization.cljs`'s `active-locale-ref`; refs.cljs is NOT touched).

  Byte-identical-when-inactive: comments are opt-in. With `:comments-mode?`
  false (the default) the comments overlay renders nothing and the canvas is
  byte-identical to today. A shape/page with no comments slot renders
  exactly as today — `read-comments`/`read-page-comments` return nil/empty.

  Exported symbols (stable interface — DO NOT rename):
    read-comments                  [shape] -> vec of comments | []
    read-page-comments             [page]  -> vec of comments | []
    add-comment-event              [shape-id comment] (one undo, shape slot)
    add-page-comment-event         [comment] (one undo, page slot)
    resolve-comment-event          [shape-id comment-id] (one undo)
    resolve-page-comment-event     [comment-id] (one undo)
    delete-comment-event           [shape-id comment-id] (one undo)
    delete-page-comment-event      [comment-id] (one undo)
    toggle-comments-mode           UpdateEvent (no file change)
    comments-mode-ref              okulary derived ref (reactive)
    comments-mode?                 [state] -> bool (default false)
    new-comment                    [author body x y] -> comment map
    post-comment-to-webhook        [comment webhook-url provider] -> promise
    load-webhook-url / save-webhook-url
    load-webhook-provider / save-webhook-provider
    generate-share-link            [file-id] -> string"
  (:require
   ["@tauri-apps/api/core" :refer [invoke]]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.undo :as dwu]
   [app.main.store :as st]
   [beicon.v2.core :as rx]
   [cljs.reader :as reader]
   [clojure.string :as str]
   [okulary.core :as l]
   [potok.v2.core :as ptk]))

;; --- Plugin-data slot constants --------------------------------------------

(def ovion-namespace :ovion)
(def shape-comments-key "comments")
(def page-comments-key "page-comments")

;; --- Comment model ---------------------------------------------------------

(defn new-comment
  "Build a fresh comment map. `author`/`body` are strings; `x`/`y` are page
  pixel coordinates (the canvas drop point for a page comment, or the
  shape-relative anchor for a shape comment). `:resolved?` defaults false."
  ([author body x y]
   (new-comment author body x y false))
  ([author body x y resolved?]
   {:id        (str (random-uuid))
    :author    (str author)
    :body      (str body)
    :x         (if (number? x) x 0)
    :y         (if (number? y) y 0)
    :resolved? (boolean resolved?)}))

;; --- Read helpers ----------------------------------------------------------

(defn read-comments
  "Parse a shape's comments slot back into a vector of comment maps.
  Accepts a shape map (reads :plugin-data) or a raw stored string. Returns
  `[]` when absent, empty, or unparsable (nil-safe: a shape with no comments
  slot is an empty vector, never nil — callers can always `seq`/`count`)."
  ([]
   [])
  ([shape-or-str]
   (let [raw (if (map? shape-or-str)
               (dm/get-in shape-or-str [:plugin-data ovion-namespace shape-comments-key])
               shape-or-str)]
     (if (or (nil? raw) (empty? raw))
       []
       (try
         (let [parsed (reader/read-string raw)]
           (if (vector? parsed) (vec parsed) []))
         (catch :default _ []))))))

(defn read-page-comments
  "Parse a page's page-comments slot back into a vector of comment maps.
  Accepts a page map (reads :plugin-data) or a raw stored string. Returns
  `[]` when absent, empty, or unparsable."
  ([]
   [])
  ([page-or-str]
   (let [raw (if (map? page-or-str)
               (dm/get-in page-or-str [:plugin-data ovion-namespace page-comments-key])
               page-or-str)]
     (if (or (nil? raw) (empty? raw))
       []
       (try
         (let [parsed (reader/read-string raw)]
           (if (vector? parsed) (vec parsed) []))
         (catch :default _ []))))))

;; --- Reactive ref + app-level state ----------------------------------------

(def comments-mode-ref
  "Derived ref over the app-level `:comments-mode?` slot. Components
  `mf/deref` this to re-render only when comments mode actually toggles
  (okulary `=` equality). Derefs to false when absent (the default — the
  canvas renders byte-identical, no overlay). Mirrors
  `localization/active-locale-ref`."
  (l/derived :comments-mode? st/state))

(defn comments-mode?
  "Read the app-level comments-mode flag from `state`. Defaults to false
  when absent (no toggle yet). Use this inside ptk events / watchers; use
  `comments-mode-ref` from React/Rumext components."
  [state]
  (boolean (:comments-mode? state)))

(defn toggle-comments-mode
  "ptk event: flip the canvas-wide comments mode. An UpdateEvent — it only
  mutates app-level state, no file change / no undo. The comments overlay
  + header toggle button subscribe via `comments-mode-ref`."
  []
  (ptk/reify ::toggle-comments-mode
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :comments-mode? (not (comments-mode? state))))))

;; --- Pure changes helpers --------------------------------------------------

(defn- set-shape-comments
  "Pure changes fn: write the comments vector on shape `shape-id`
  (page `page-id`). `comments` is a vector of comment maps, or nil/empty to
  clear the slot. `changes` must carry file-data + page context."
  [changes shape-id page-id comments]
  (let [value (if (or (nil? comments) (empty? comments)) "" (pr-str comments))]
    (pcb/set-plugin-data changes :shape shape-id page-id
                         ovion-namespace shape-comments-key value)))

(defn- set-page-comments
  "Pure changes fn: write the page-comments vector on the page `page-id`.
  `comments` is a vector of comment maps, or nil/empty to clear. `changes`
  must carry file-data + page context (mirrors plugin_registry.cljs's
  page-level set-plugin-data arity)."
  [changes page-id comments]
  (let [value (if (or (nil? comments) (empty? comments)) "" (pr-str comments))]
    (pcb/set-plugin-data changes :page page-id
                         ovion-namespace page-comments-key value)))

;; --- Event commit helpers (one undo transaction) ---------------------------

(defn- commit-shape-comments
  "Build + commit a shape-level comments plugin-data change in one undo
  transaction. `update-fn` is applied to the existing comments vector (or
  [] when absent) and must return the new vector (or nil/empty to clear)."
  [it state shape-id update-fn]
  (let [page-id   (:current-page-id state)
        page      (dsh/lookup-page state)
        file-id   (:current-file-id state)
        file-data (dsh/lookup-file-data state file-id)]
    (if (or (nil? page) (nil? shape-id))
      (rx/empty)
      (let [undo-id  (js/Symbol)
            shape    (get-in page [:objects shape-id])
            existing (read-comments shape)
            new-vec  (update-fn existing)
            value    (if (or (nil? new-vec) (empty? new-vec)) "" (pr-str new-vec))]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes
                (-> (pcb/empty-changes it)
                    (pcb/with-file-data file-data)
                    (pcb/with-page page)
                    (pcb/set-plugin-data :shape shape-id page-id
                                         ovion-namespace shape-comments-key value)))
               (dwu/commit-undo-transaction undo-id))))))

(defn- commit-page-comments
  "Build + commit a page-level page-comments plugin-data change in one undo
  transaction. `update-fn` is applied to the existing page comments vector
  (or [] when absent) and must return the new vector (or nil/empty to clear)."
  [it state update-fn]
  (let [page      (dsh/lookup-page state)
        file-id   (:current-file-id state)
        file-data (dsh/lookup-file-data state file-id)]
    (if (nil? page)
      (rx/empty)
      (let [undo-id  (js/Symbol)
            existing (read-page-comments page)
            new-vec  (update-fn existing)
            value    (if (or (nil? new-vec) (empty? new-vec)) "" (pr-str new-vec))]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes
                (-> (pcb/empty-changes it)
                    (pcb/with-file-data file-data)
                    (pcb/with-page page)
                    (pcb/set-plugin-data :page (:id page)
                                         ovion-namespace page-comments-key value)))
               (dwu/commit-undo-transaction undo-id))))))

;; --- Events ----------------------------------------------------------------

(defn add-comment-event
  "ptk event: append `comment` (a map from `new-comment`) to shape
  `shape-id`'s comments slot in one undo transaction. Initializes the slot
  if absent."
  [shape-id comment]
  (ptk/reify ::add-comment
    ptk/WatchEvent
    (watch [it state _]
      (commit-shape-comments
       it state shape-id
       (fn [existing]
         (conj (vec existing) comment))))))

(defn add-page-comment-event
  "ptk event: append `comment` (a map from `new-comment`) to the current
  page's page-comments slot in one undo transaction."
  [comment]
  (ptk/reify ::add-page-comment
    ptk/WatchEvent
    (watch [it state _]
      (commit-page-comments
       it state
       (fn [existing]
         (conj (vec existing) comment))))))

(defn resolve-comment-event
  "ptk event: toggle `:resolved?` on the comment with `comment-id` in shape
  `shape-id`'s comments slot, in one undo transaction. No-op if the comment
  is absent."
  [shape-id comment-id]
  (ptk/reify ::resolve-comment
    ptk/WatchEvent
    (watch [it state _]
      (commit-shape-comments
       it state shape-id
       (fn [existing]
         (vec (map (fn [c]
                     (if (= (:id c) comment-id)
                       (assoc c :resolved? (not (:resolved? c false)))
                       c))
                   existing)))))))

(defn resolve-page-comment-event
  "ptk event: toggle `:resolved?` on the page comment with `comment-id`, in
  one undo transaction."
  [comment-id]
  (ptk/reify ::resolve-page-comment
    ptk/WatchEvent
    (watch [it state _]
      (commit-page-comments
       it state
       (fn [existing]
         (vec (map (fn [c]
                     (if (= (:id c) comment-id)
                       (assoc c :resolved? (not (:resolved? c false)))
                       c))
                   existing)))))))

(defn delete-comment-event
  "ptk event: remove the comment with `comment-id` from shape `shape-id`'s
  comments slot, in one undo transaction. Clears the slot when the last
  comment is removed (so the shape is byte-identical to pre-comment state)."
  [shape-id comment-id]
  (ptk/reify ::delete-comment
    ptk/WatchEvent
    (watch [it state _]
      (commit-shape-comments
       it state shape-id
       (fn [existing]
         (let [next (vec (remove #(= (:id %) comment-id) existing))]
           (if (empty? next) nil next)))))))

(defn delete-page-comment-event
  "ptk event: remove the page comment with `comment-id`, in one undo
  transaction. Clears the slot when the last comment is removed."
  [comment-id]
  (ptk/reify ::delete-page-comment
    ptk/WatchEvent
    (watch [it state _]
      (commit-page-comments
       it state
       (fn [existing]
         (let [next (vec (remove #(= (:id %) comment-id) existing))]
           (if (empty? next) nil next)))))))

;; --- Webhook integrations (Slack/Teams/Jira/Confluence) --------------------
;;
;; The webhook URL + provider live in browser localStorage, user-supplied
;; (key-gated like the Pexels key in stock_assets.cljs). `post-comment-to-
;; webhook` shapes the payload per provider and invokes the Rust
;; `post_webhook` command (src-tauri/src/team_sharing.rs), which POSTs JSON
;; and returns the body or a sentinel (`webhook-url-missing` /
;; `webhook-post-failed`).

(def ^:private webhook-url-storage-key "ovion.webhook-url")
(def ^:private webhook-provider-storage-key "ovion.webhook-provider")

(defn load-webhook-url
  "Read the saved webhook URL from localStorage. Returns \"\" when unset or
  unavailable (nil-safe against browsers that throw on localStorage)."
  []
  (try
    (or (.getItem js/localStorage webhook-url-storage-key) "")
    (catch :default _ "")))

(defn save-webhook-url
  "Persist `url` to localStorage and return it. Empty string clears the
  URL (re-enables the not-configured state in the comments menu)."
  [url]
  (try
    (if (str/blank? url)
      (.removeItem js/localStorage webhook-url-storage-key)
      (.setItem js/localStorage webhook-url-storage-key url))
    (catch :default _))
  url)

(defn load-webhook-provider
  "Read the saved webhook provider keyword (`:slack`/`:teams`/`:jira`/
  `:confluence`) from localStorage. Returns `:slack` when unset (the
  default)."
  []
  (try
    (let [raw (.getItem js/localStorage webhook-provider-storage-key)]
      (if (or (nil? raw) (empty? raw))
        :slack
        (keyword raw)))
    (catch :default _ :slack)))

(defn save-webhook-provider
  "Persist `provider` (a keyword or string) to localStorage and return it
  as a keyword. Empty/nil resets to the default `:slack`."
  [provider]
  (let [kw (if (nil? provider) :slack (keyword provider))]
    (try
      (if (or (nil? provider) (str/blank? (str provider)))
        (.removeItem js/localStorage webhook-provider-storage-key)
        (.setItem js/localStorage webhook-provider-storage-key (str kw)))
      (catch :default _))
    kw))

(defn webhook-url-set?
  "True when a non-blank webhook URL is saved in localStorage."
  []
  (not (str/blank? (load-webhook-url))))

(defn- webhook-payload
  "Build the JSON string payload for `provider` carrying `comment`.
  Slack's incoming-webhook contract is `{\"text\": \"…\"}` (a single text
  field); Teams/Jira/Confluence accept a generic JSON object, so we send
  `{provider, author, body, shapeId}` for structured tooling. `shape-id`
  is nil for page-floating comments."
  [provider comment shape-id]
  (let [provider-kw (if (nil? provider) :slack (keyword provider))]
    (case provider-kw
      :slack
      (js/JSON.stringify
       #js {:text (str (:author comment "Anonymous") ": " (:body comment ""))})
      ;; :teams / :jira / :confluence / default — generic JSON envelope.
      (js/JSON.stringify
       #js {:provider (str provider-kw)
            :author   (:author comment "Anonymous")
            :body     (:body comment "")
            :shapeId  (if (some? shape-id) (str shape-id) nil)}))))

(defn post-comment-to-webhook
  "POST `comment` to the configured `webhook-url` for `provider`. Returns a
  promesa promise resolving to the response body text, or rejecting with a
  sentinel string (`webhook-url-missing` / `webhook-post-failed`) the menu
  matches to render the right state. `shape-id` is nil for page-floating
  comments."
  ([comment]
   (post-comment-to-webhook comment (load-webhook-url) (load-webhook-provider)))
  ([comment webhook-url provider]
   (let [shape-id (:shape-id comment)
         payload  (webhook-payload provider comment shape-id)]
     (invoke "post_webhook" #js {:url webhook-url :payload payload}))))

;; --- Share-link generation -------------------------------------------------
;;
;; No deep-link scheme is registered in tauri.conf.json (no
;; `associated-domains`/`deep-link` config), so we mint a placeholder HTTPS
;; share URL. The QR is rendered in the menu via the api.qrserver.com image
;; service (no QR library dependency).

(defn- base64-url-safe
  "URL-safe base64 of a string: standard `btoa` then swap `+`->`-`,
  `/`->`_`, strip `=` padding. Good enough for a UUID `file-id` (ascii)."
  [s]
  (let [raw (js/btoa (str s))]
    (-> raw
        (str/replace "+" "-")
        (str/replace "/" "_")
        (str/replace "=" ""))))

(def share-link-base
  "Placeholder share origin. No deep-link is registered with the OS, so this
  is an HTTPS URL the QR encodes + the copy button copies. Post-hosting this
  becomes the Ovion Cloud publish URL."
  "https://ovion.app/share/")

(defn generate-share-link
  "Mint a share link for `file-id`: `<base><base64-url-safe-file-id>`.
  `file-id` is typically a UUID string. Returns the full URL string. Pure —
  no side effects; the menu handles copy + QR rendering."
  [file-id]
  (str share-link-base (base64-url-safe (str file-id))))

(defn qr-image-url
  "Build a QR-code image URL for `link` via the api.qrserver.com image
  service (no QR library). `size` defaults to 160x160 — small enough for the
  inspector menu, large enough to scan from screen."
  ([link]
   (qr-image-url link 160))
  ([link size]
   (let [px (str size "x" size)]
     (str "https://api.qrserver.com/v1/create-qr-code/?size=" px
          "&data=" (js/encodeURIComponent (str link))))))