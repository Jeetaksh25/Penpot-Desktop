;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.css-anim
  "CSS-keyframe animation presets (ALL_APPS_PARITY P2.06).

  A catalog of named CSS @keyframes presets (fade-in, slide-up, pulse,
  bounce-soft, spin) each defined as a CSS string. A shape carries
  `:ovion \"css-anim\"` = pr-str `{:preset <keyword> :duration <ms>
  :delay <ms> :iteration <int|:infinite>}`. At render time the viewer
  emits the class + @keyframes for the shape's DOM node (reduced-motion
  guarded — disabled under prefers-reduced-motion).

  This module OWNS:
    * `preset-catalog` — the vector of preset descriptors
      `{:id <kw> :label <str> :keyframes <css-str>}`.
    * `read-css-anim` — parse a shape's css-anim slot back into a cfg
      map, or nil (nil = no animation -> byte-identical render).
    * `css-for-anim` — build the full CSS (class rule + @keyframes) for a
      cfg, or nil when the preset is unknown / cfg nil. The class name is
      `ovion-anim-<preset>`; the rule sets `animation: <preset>
      <duration>ms <delay>ms <iteration> <easing>`. Reduced-motion is the
      CALLER's responsibility (the viewer hook checks
      `prefers-reduced-motion` and skips emitting when set) — this keeps
      `css-for-anim` pure and testable.
    * `set-css-anim` / `clear-css-anim` — undo-safe commit events
      (shape-level plugin-data, mirrors motion_effects.cljs).

  Render hook (the lead must wire this in viewer/shapes.cljs — see the
  css_anim menu docstring for the exact file + line)."

  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [cljs.reader :as reader]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

;; --- Plugin-data slot constants --------------------------------------------

(def ovion-namespace :ovion)
(def css-anim-key "css-anim")

;; --- Preset catalog --------------------------------------------------------

;; Each preset's :keyframes is the raw CSS @keyframes block (sans the
;; leading `@keyframes <name> {` wrapper — `css-for-anim` adds it). The
;; keyframe body uses standard CSS so it round-trips through a <style>
;; element injection at render time. Easing is per-preset (ease-out for
;; entrances, ease-in-out for loops).

(def preset-catalog
  "Vector of `{:id :label :keyframes :easing}`. `:id` is the keyword the
  css-anim slot stores; `:label` is the UI label key (resolved by the
  menu via tr). `:keyframes` is the inner CSS of the @keyframes body.
  `:easing` is the default timing function for the class rule."
  [{:id :fade-in
    :label "workspace.options.css-anim.preset.fade-in"
    :easing "ease-out"
    :keyframes "0% { opacity: 0; } 100% { opacity: 1; }"}
   {:id :slide-up
    :label "workspace.options.css-anim.preset.slide-up"
    :easing "ease-out"
    :keyframes "0% { opacity: 0; transform: translateY(24px); } 100% { opacity: 1; transform: translateY(0); }"}
   {:id :slide-down
    :label "workspace.options.css-anim.preset.slide-down"
    :easing "ease-out"
    :keyframes "0% { opacity: 0; transform: translateY(-24px); } 100% { opacity: 1; transform: translateY(0); }"}
   {:id :slide-left
    :label "workspace.options.css-anim.preset.slide-left"
    :easing "ease-out"
    :keyframes "0% { opacity: 0; transform: translateX(24px); } 100% { opacity: 1; transform: translateX(0); }"}
   {:id :slide-right
    :label "workspace.options.css-anim.preset.slide-right"
    :easing "ease-out"
    :keyframes "0% { opacity: 0; transform: translateX(-24px); } 100% { opacity: 1; transform: translateX(0); }"}
   {:id :pulse
    :label "workspace.options.css-anim.preset.pulse"
    :easing "ease-in-out"
    :keyframes "0% { opacity: 1; } 50% { opacity: 0.4; } 100% { opacity: 1; }"}
   {:id :bounce-soft
    :label "workspace.options.css-anim.preset.bounce-soft"
    :easing "cubic-bezier(0.34, 1.56, 0.64, 1)"
    :keyframes "0% { transform: translateY(0); } 30% { transform: translateY(-12px); } 60% { transform: translateY(0); } 100% { transform: translateY(0); }"}
   {:id :spin
    :label "workspace.options.css-anim.preset.spin"
    :easing "linear"
    :keyframes "0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); }"}
   {:id :shake
    :label "workspace.options.css-anim.preset.shake"
    :easing "ease-in-out"
    :keyframes "0%, 100% { transform: translateX(0); } 20% { transform: translateX(-6px); } 40% { transform: translateX(6px); } 60% { transform: translateX(-4px); } 80% { transform: translateX(4px); }"}
   {:id :zoom-in
    :label "workspace.options.css-anim.preset.zoom-in"
    :easing "ease-out"
    :keyframes "0% { opacity: 0; transform: scale(0.85); } 100% { opacity: 1; transform: scale(1); }"}])

(defn preset-by-id
  "Return the preset descriptor for `id` (keyword), or nil."
  [id]
  (when (some? id)
    (first (filter #(= (:id %) id) preset-catalog))))

(def preset-ids
  "Set of valid preset id keywords (used for validation)."
  (into #{} (map :id) preset-catalog))

;; --- Read helper -----------------------------------------------------------

(defn read-css-anim
  "Parse a shape's css-anim slot back into a config map
  `{:preset :duration :delay :iteration}`, or nil when absent / unparsable
  / the preset is unknown. Accepts a shape map (reads :plugin-data) or a
  raw stored string. nil = no animation -> the viewer renders the shape
  normally (byte-identical)."
  ([]
   nil)
  ([shape-or-str]
   (let [raw (if (map? shape-or-str)
               (dm/get-in shape-or-str [:plugin-data ovion-namespace css-anim-key])
               shape-or-str)]
     (if (or (nil? raw) (empty? raw))
       nil
       (try
         (let [v (reader/read-string raw)]
           (if (and (map? v) (contains? preset-ids (:preset v)))
             v
             nil))
         (catch :default _ nil))))))

;; --- CSS builder -----------------------------------------------------------

(defn- iteration-str
  "Coerce an iteration value (`:infinite` keyword or int) to its CSS
  representation."
  [iter]
  (cond
    (or (= iter :infinite) (= iter "infinite")) "infinite"
    (int? iter)                                  (str iter)
    :else                                        "1"))

(defn class-name-for
  "Return the CSS class name for a preset id (`ovion-anim-<preset>`)."
  [preset-id]
  (str "ovion-anim-" (d/name preset-id)))

(defn css-for-anim
  "Build the full CSS string (class rule + @keyframes) for a css-anim
  config map `{:preset :duration :delay :iteration}`, or nil when the
  preset is unknown / cfg is nil. The class rule targets
  `.ovion-anim-<preset>` and sets `animation`. Reduced-motion handling is
  the CALLER's responsibility (the viewer render hook skips emitting when
  prefers-reduced-motion is set) — this keeps the builder pure.

  The returned CSS is suitable for injection into a <style> element scoped
  to the shape's DOM node. Example output for fade-in, 600ms, 0 delay, 1
  iteration:

    .ovion-anim-fade-in { animation: fade-in 600ms 0ms 1 ease-out; }
    @keyframes fade-in { 0% { opacity: 0; } 100% { opacity: 1; } }"
  [cfg]
  (when (some? cfg)
    (let [preset-id (:preset cfg)
          preset    (preset-by-id preset-id)]
      (when (some? preset)
        (let [cls   (class-name-for preset-id)
              nm    (d/name preset-id)
              dur   (or (:duration cfg) 600)
              dly   (or (:delay cfg) 0)
              iter  (iteration-str (:iteration cfg))
              ease  (:easing preset "ease-out")
              kf    (:keyframes preset)]
          (str "." cls " { animation: " nm " " dur "ms " dly "ms " iter " " ease "; } "
               "@keyframes " nm " { " kf " }"))))))

;; --- Commit helper ---------------------------------------------------------

(defn- commit-plugin-data
  "Build and commit a changeset that writes `value` (pr-str'd; nil clears)
  to shape `shape-id`'s css-anim slot. One undo transaction. Mirrors
  motion_effects.cljs's `commit-plugin-data`."
  [it state shape-id value]
  (let [page-id   (:current-page-id state)
        page      (dsh/lookup-page state)
        file-id   (:current-file-id state)
        file-data (dsh/lookup-file-data state file-id)]
    (if (or (nil? page) (nil? shape-id))
      (rx/empty)
      (let [v       (if (nil? value) nil (pr-str value))
            changes (-> (pcb/empty-changes it)
                        (pcb/with-file-data file-data)
                        (pcb/with-page page)
                        (pcb/set-plugin-data :shape
                                             shape-id
                                             page-id
                                             ovion-namespace
                                             css-anim-key
                                             v))
            undo-id (js/Symbol)]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes changes)
               (dwu/commit-undo-transaction undo-id))))))

;; --- Events ----------------------------------------------------------------

(defn set-css-anim
  "Set the css-anim config on shape `shape-id`. `cfg` is
  `{:preset :duration :delay :iteration}` or nil to clear. Validates
  :preset against preset-ids; an invalid preset is a no-op. One undo."
  [{:keys [shape-id cfg]}]
  (ptk/reify ::set-css-anim
    ptk/WatchEvent
    (watch [it state _]
      (if (and (some? cfg) (not (contains? preset-ids (:preset cfg))))
        (rx/empty)
        (commit-plugin-data it state shape-id cfg)))))

(defn clear-css-anim
  "Remove the css-anim slot from shape `shape-id`. One undo."
  [{:keys [shape-id]}]
  (ptk/reify ::clear-css-anim
    ptk/WatchEvent
    (watch [it state _]
      (commit-plugin-data it state shape-id nil))))