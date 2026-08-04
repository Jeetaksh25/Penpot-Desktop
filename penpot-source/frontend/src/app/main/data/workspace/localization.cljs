;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.localization
  "P2.25 — Localization / multi-locale content — READ/RENDER layer
  (ALL_APPS_PARITY).

  A locale model for designed content (a CMS-style content layer), with
  locale switching and per-locale translated-content rendering for text
  shapes. Three storage layers:

    FILE-level plugin-data `:ovion \"locales\"`
        pr-str of a vector of locale keywords `[:en :es :fr ...]` — the
        project's enabled locales. Default `[:en]` when absent.

    SHAPE-level plugin-data `:ovion \"locale-strings\"`
        pr-str of a map `{<locale-kw> <string>}` on a text shape — the
        translated content per locale. A shape carrying this slot renders
        the ACTIVE locale's string (fallback: active -> default -> the
        shape's own content, i.e. byte-identical original render).

    APP-level state `:active-locale`
        a keyword (default absent/nil = no switch = render default content)
        + `set-active-locale-event`. Read via `active-locale` (state fn) or
        `active-locale-ref` (okulary derived ref for reactive components).

  Byte-identical-when-inactive: a text shape WITHOUT the locale-strings
  slot renders exactly as today — `localized-shape` returns nil and the
  caller falls through to the pre-existing shape. `:active-locale` absent
  (nil) also short-circuits to the original render, so a project that never
  switches locale is byte-identical.

  This is the READ/RENDER namespace only — pure functions + the reactive
  `active-locale-ref`. It deliberately does NOT require
  `app.main.data.changes` so the render path (`app.main.ui.shapes.text`,
  pulled transitively by `app.main.data.changes` via features → render-wasm
  → render-wasm.api) does not close a compile-time circular dependency. The
  mutation/event half lives in `app.main.data.workspace.localization.events`.

  Exported symbols (stable interface — DO NOT rename):
    read-locales                       [file-data] -> vec of kw | [:en]
    read-locale-strings                [shape] -> {kw str} | nil
    locale-string-for                 [shape active default] -> str | nil
    localized-shape                   [shape active default] -> shape | nil
    shape-own-text                     [shape] -> str
    active-locale                      [state] -> kw (default :en)
    active-locale-ref                  okulary derived ref (reactive)
    ovion-namespace / locales-key / slot-key / default-locale  (constants)"
  (:require
   [app.common.data.macros :as dm]
   [app.main.store :as st]
   [cljs.reader :as reader]
   [okulary.core :as l]))

;; --- Plugin-data slot constants --------------------------------------------

(def ovion-namespace :ovion)
(def locales-key "locales")
(def slot-key "locale-strings")

(def default-locale :en)

;; --- Read helpers -----------------------------------------------------------

(defn read-locales
  "Parse the file-level enabled-locales vector back into a vector of
  keywords. Accepts a file-data map (reads :plugin-data) or a raw stored
  string. Returns `[:en]` when absent, empty, or unparsable (nil-safe:
  a project with no locale model has the single default locale)."
  ([]
   [:en])
  ([file-data-or-str]
   (let [raw (if (map? file-data-or-str)
               (dm/get-in file-data-or-str [:plugin-data ovion-namespace locales-key])
               file-data-or-str)]
     (if (or (nil? raw) (empty? raw))
       [:en]
       (try
         (let [parsed (reader/read-string raw)]
           (if (and (vector? parsed) (seq parsed))
             (vec parsed)
             [:en]))
         (catch :default _ [:en]))))))

(defn read-locale-strings
  "Parse a shape's locale-strings slot back into a map `{<kw> <string>}`.
  Accepts a shape map (reads :plugin-data) or a raw stored string. Returns
  nil when absent or unparsable (nil = not locale-managed = render normally)."
  ([]
   nil)
  ([shape-or-str]
   (let [raw (if (map? shape-or-str)
               (dm/get-in shape-or-str [:plugin-data ovion-namespace slot-key])
               shape-or-str)]
     (if (or (nil? raw) (empty? raw))
       nil
       (try
         (reader/read-string raw)
         (catch :default _ nil))))))

(defn- first-node-of-type
  "Depth-first search for the first content node with `type` (e.g.
  \"paragraph-set\", \"paragraph\"). Returns nil if none."
  [content type-str]
  (->> (tree-seq map? :children content)
       (filter #(= type-str (:type %)))
       first))

(defn- first-text-leaf
  "Depth-first search for the first text leaf node (a node with no :type
  and a string :text). Returns nil if the content has no text run."
  [content]
  (->> (tree-seq map? :children content)
       (filter #(and (nil? (:type %)) (string? (:text %))))
       first))

(defn shape-own-text
  "Extract a plain-text representation of a text shape's current content —
  the first text run's string from the rich-text content tree, falling back
  to the first laid-out position-data block's text. Returns \"\" when no
  text can be extracted. Used by `enable-locale-strings-on-shape-event` to
  seed the default-locale string, and as the documentation fallback."
  [shape]
  (or (some-> (:content shape) first-text-leaf :text)
      (some-> (:position-data shape) first :text)
      ""))

(defn locale-string-for
  "Return the string to render for `shape` under `active-locale` with
  `default-locale` as the secondary fallback. Returns nil when:
    - the shape has no locale-strings slot (not locale-managed), OR
    - neither `active-locale` nor `default-locale` is present in the slot
      (the caller then renders the shape's own content, byte-identical).
  Otherwise returns the active-locale string, or the default-locale string
  when the active locale is absent."
  [shape active-locale default-locale]
  (let [strings (read-locale-strings shape)]
    (when (some? strings)
      (or (get strings active-locale)
          (get strings default-locale)))))

(defn- localized-content
  "Build a minimal single-run content tree that renders `locale-str` in the
  style of the first paragraph / first text run. Preserves the root node's
  attrs (vertical-align, fills) and the first paragraph's + first text
  leaf's styling. Returns nil if the content tree has no paragraph or text
  leaf to clone the style from."
  [content locale-str]
  (let [ps   (first-node-of-type content "paragraph-set")
        para (first-node-of-type content "paragraph")
        leaf (first-text-leaf content)]
    (when (and ps para leaf)
      (let [new-leaf  (assoc leaf :text locale-str)
            new-para  (assoc para :children [new-leaf])
            new-ps    (assoc ps :children [new-para])
            root-base (dissoc content :children)]
        (assoc root-base :children [new-ps])))))

(defn- localized-position-data
  "Build a single-block position-data vector that renders `locale-str` at
  the first laid-out block's position with the first block's font/fill
  styling. Returns nil when the shape has no position-data."
  [position-data locale-str]
  (when (and (vector? position-data) (pos? (count position-data)))
    [(assoc (first position-data) :text locale-str)]))

(defn localized-shape
  "Return a modified copy of `shape` with its text content substituted for
  the active-locale string (per the fallback chain in `locale-string-for`),
  or nil when the shape should render its original content (no slot, no
  active-locale match, or no active-locale). The caller falls through to
  the original shape when this returns nil — byte-identical rendering."
  [shape active-locale default-locale]
  (let [locale-str (locale-string-for shape active-locale default-locale)]
    (when (some? locale-str)
      (let [new-content (some-> (:content shape) (localized-content locale-str))
            new-pd      (localized-position-data (:position-data shape) locale-str)]
        (cond-> shape
          (some? new-content) (assoc :content new-content)
          (some? new-pd)      (assoc :position-data new-pd))))))

;; --- Reactive ref -----------------------------------------------------------

(def active-locale-ref
  "Derived ref over the app-level `:active-locale` slot. Components
  `mf/deref` this to re-render only when the active locale actually changes
  (okulary `=` equality — unrelated state changes do not re-render
  subscribers). Derefs to nil when no locale has been switched (the default
  — text shapes render byte-identical original content)."
  (l/derived :active-locale st/state))

(defn active-locale
  "Read the app-level active-locale keyword from `state`. Defaults to `:en`
  when absent (no switch yet). Use this inside ptk events / watchers; use
  `active-locale-ref` from React/Rumext components."
  [state]
  (or (:active-locale state) default-locale))