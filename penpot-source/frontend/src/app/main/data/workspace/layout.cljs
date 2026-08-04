;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.layout
  "Workspace layout management events and helpers."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.data.event :as ev]
   [app.util.storage :as storage]
   [clojure.set :as set]
   [potok.v2.core :as ptk]))

(def valid-flags
  #{:sitemap
    :layers
    :comments
    :assets
    :document-history
    :hide-palettes
    :colorpalette
    :element-options
    :rulers
    :display-guides
    :display-comments
    :lock-guides
    :snap-guides
    :scale-text
    :dynamic-alignment
    :display-artboard-names
    :snap-ruler-guides
    :show-pixel-grid
    :snap-pixel-grid
    ;; Figma-parity viewport render modes (gaps #45/#46/#51). All three
    ;; default to absent (byte-identical rendering/interaction when the
    ;; flag is off). :outline-mode renders shapes as stroked outlines
    ;; only; :pixel-preview rasterizes the canvas at device pixels;
    ;; :lasso-mode enables the freehand lasso selection widget.
    :outline-mode
    :pixel-preview
    :lasso-mode
    ;; Figma-parity command palette (gap #47). When set, the
    ;; command-palette overlay mounts.
    :command-palette
    ;; Color-blindness simulator (ALL_APPS_PARITY P1.09). At most one of
    ;; these flags is ever present in :workspace-layout at a time —
    ;; `set-color-blindness-mode` clears the whole group before adding the
    ;; chosen mode (or adds nothing for :none). All default to absent, so
    ;; rendering is byte-identical when no simulator is active.
    :color-blindness/deuteranopia
    :color-blindness/protanopia
    :color-blindness/tritanopia
    :color-blindness/achromatopsia})

(def presets
  {:assets
   {:del #{:sitemap :layers :document-history}
    :add #{:assets}}

   :document-history
   {:del #{:assets :layers :sitemap}
    :add #{:document-history}}

   :layers
   {:del #{:document-history :assets}
    :add #{:sitemap :layers}}

   :tokens
   {:del #{:sitemap :layers :document-history :assets}
    :add #{:tokens}}})

(def valid-options-mode
  #{:design :prototype :inspect :debug})

(def default-layout
  #{:sitemap
    :layers
    :element-options
    :rulers
    :display-guides
    :display-comments
    :snap-guides
    :dynamic-alignment
    :display-artboard-names
    :snap-ruler-guides
    :show-pixel-grid
    :snap-pixel-grid})

(def default-global
  {:options-mode :design})

(defn ensure-layout
  [name]
  (ptk/reify ::ensure-layout
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-layout
              (fn [stored]
                (let [todel (get-in presets [name :del] #{})
                      toadd (get-in presets [name :add] #{})]
                  (-> stored
                      (set/difference todel)
                      (set/union toadd))))))))

(declare persist-layout-flags!)

(defn toggle-layout-flag
  [flag & {:keys [force?] :as opts}]
  (ptk/reify ::toggle-layout-flag
    ev/Event
    (-data [_] {:name flag})

    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-layout
              (fn [flags]
                (if force?
                  (conj flags flag)
                  (if (contains? flags flag)
                    (disj flags flag)
                    (conj flags flag))))))

    ptk/EffectEvent
    (effect [_ state _]
      (let [flags (:workspace-layout state)]
        (persist-layout-flags! flags)))))

(defn remove-layout-flag
  [flag]
  (ptk/reify ::remove-layout-flag
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-layout
              (fn [flags]
                (disj flags flag))))

    ptk/EffectEvent
    (effect [_ state _]
      (let [flags (:workspace-layout state)]
        (persist-layout-flags! flags)))))

(def ^:private color-blindness-flag->mode
  "Map of layout flag -> simulator mode keyword. The single source of
  truth for the flag/mode pairing (ALL_APPS_PARITY P1.09)."
  {:color-blindness/deuteranopia :deuteranopia
   :color-blindness/protanopia   :protanopia
   :color-blindness/tritanopia   :tritanopia
   :color-blindness/achromatopsia :achromatopsia})

(def ^:private color-blindness-flags
  "The mutually-exclusive set of color-blindness simulator flags. Exactly one
  (or none) is present in :workspace-layout at any time."
  (set (keys color-blindness-flag->mode)))

(def ^:private color-blindness-mode->flag
  "Reverse map of simulator mode keyword -> layout flag. :none has no
  entry (it clears the group)."
  (into {} (for [[flag mode] color-blindness-flag->mode]
             [mode flag])))

(defn active-color-blindness-mode
  "Return the active color-blindness simulator mode keyword present in the
  given `:workspace-layout` flag set, or nil when no simulator flag is
  set. Shared by the viewport renderer and the toolbar Vision menu so the
  flag->mode mapping lives in exactly one place."
  [layout]
  (let [hit (some #(when (contains? layout %) %)
                  color-blindness-flags)]
    (get color-blindness-flag->mode hit)))

(defn set-color-blindness-mode
  "Set the active color-blindness simulator mode (ALL_APPS_PARITY P1.09).

  `mode` is one of :deuteranopia, :protanopia, :tritanopia,
  :achromatopsia or :none. The whole `color-blindness-flags` group is
  cleared first, then the chosen mode's flag is added — so the group is
  always mutually exclusive and :none leaves no flag set. The result is
  persisted exactly like `toggle-layout-flag`."
  [mode]
  (dm/assert!
   "expected valid color-blindness mode"
   (or (contains? color-blindness-mode->flag mode)
       (= mode :none)))
  (let [flag (get color-blindness-mode->flag mode)]
    (ptk/reify ::set-color-blindness-mode
      ev/Event
      (-data [_] {:name :color-blindness :mode (d/name mode)})

      ptk/UpdateEvent
      (update [_ state]
        (update state :workspace-layout
                (fn [flags]
                  (let [flags (set/difference flags color-blindness-flags)]
                    (if flag (conj flags flag) flags)))))

      ptk/EffectEvent
      (effect [_ state _]
        (let [flags (:workspace-layout state)]
          (persist-layout-flags! flags))))))

(defn set-options-mode
  [mode]
  (dm/assert!
   "expected valid options mode"
   (contains? valid-options-mode mode))

  (ptk/reify ::set-options-mode
    ev/Event
    (-data [_]
      {::ev/origin "workspace:sidebar"
       :mode (d/name mode)})

    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-global :options-mode] mode))))

(def layout-flags-persistence-mapping
  "A map of layout flags that should be persisted in local storage; the
  value corresponds to the key that will be used for save the data in
  storage object. It should be namespace qualified."
  {:hide-palettes :app.main.data.workspace/hide-palettes?
   :colorpalette :app.main.data.workspace/show-colorpalette?
   :textpalette :app.main.data.workspace/show-textpalette?
   :rulers :app.main.data.workspace/show-rulers?
   :display-comments :app.main.data.workspace/show-comments?})

(defn load-layout-flags
  "Given the current layout flags, and updates them with the data
  stored in Storage."
  [layout]
  (let [layout (set (or layout #{}))]
    (reduce-kv (fn [layout flag key]
                 (condp = (get storage/user key ::none)
                   ::none layout
                   false  (disj layout flag)
                   true   (conj layout flag)))
               layout
               layout-flags-persistence-mapping)))

(defn persist-layout-flags!
  "Given a set of layout flags, and persist a subset of them to the Storage."
  [layout]
  (doseq [[flag key] layout-flags-persistence-mapping]
    (swap! storage/user assoc key (contains? layout flag))))

(def layout-state-persistence-mapping
  "A mapping of keys that need to be persisted from `:workspace-global` into Storage."
  {:selected-palette :app.main.data.workspace/selected-palette
   :selected-palette-colorpicker :app.main.data.workspace/selected-palette-colorpicker})

(defn load-layout-state
  "Given state (the :workspace-global) and update it with layout related
  props that are previously persisted in the Storage."
  [state]
  (reduce (fn [state [key skey]]
            (let [val (get storage/user skey ::none)]
              (if (= val ::none)
                state
                (assoc state key val))))
          state
          layout-state-persistence-mapping))

(defn persist-layout-state!
  "Given state (the :workspace-global) and persists a subset of layout
  related props to the Storage."
  [state]
  (doseq [[key skey] layout-state-persistence-mapping]
    (let [val (get state key ::does-not-exist)]
      (if (= val ::does-not-exist)
        (swap! storage/user dissoc skey)
        (swap! storage/user assoc skey val)))))
