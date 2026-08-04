;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.material-kit
  "P2.11 — Built-in Material Design 3 component kit + Material theming.

  A curated, purely-additive Material 3 design kit the user injects from
  the Assets panel ('Add Material 3 kit'). Injection is ONE undo batch
  that:

    1. adds the M3 color-role token set to the file's library as reusable
       named color styles (light + dark variants, grouped under
       'Material 3/Light' and 'Material 3/Dark');
    2. commits a DesignSpec — a 'Material 3' board carrying one group per
       M3 component (Button, OutlinedButton, TextButton, Card, FilledCard,
       TextField, OutlinedTextField, Switch, Checkbox, FAB, AppBar,
       NavigationBar) — via the EXISTING `design-gen/apply-design-spec`
       shape-creation pipeline (cds/spec->shape-tree → pcb/add-object).
       No new shape-creation path is invented here.
    3. stamps the file with plugin-data `:ovion \"m3-tokens\"` so a second
       injection is a no-op (idempotency guard).

  Byte-identical-when-inactive: this namespace is only loaded when the
  user clicks the Assets action. A file that has never been injected is
  untouched — no tokens, no plugin-data, no shapes."

  (:require
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.types.color :as ctc]
   [app.common.types.design-spec :as cds]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace.design-gen :as design-gen]
   [app.main.data.workspace.undo :as dwu]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; ── M3 color-role tokens ─────────────────────────────────────────────────────
;;
;; The Material 3 color-role system, using Google's default baseline scheme.
;; Each token becomes a Penpot library color (named style) so fills/strokes
;; can reference it by name. `:path` groups them under Material 3/Light or
;; Material 3/Dark in the Assets color panel. Hex values are upper-cased to
;; match the rest of the codebase's color-style convention.

(def ^:private m3-light-tokens
  [{:name "Primary"                :color "#6750A4"}
   {:name "On Primary"             :color "#FFFFFF"}
   {:name "Primary Container"      :color "#EADDFF"}
   {:name "On Primary Container"   :color "#21005D"}
   {:name "Secondary"              :color "#625B71"}
   {:name "On Secondary"           :color "#FFFFFF"}
   {:name "Secondary Container"    :color "#E8DEF8"}
   {:name "On Secondary Container" :color "#1D192B"}
   {:name "Tertiary"               :color "#7D5260"}
   {:name "On Tertiary"            :color "#FFFFFF"}
   {:name "Tertiary Container"     :color "#FFD8E4"}
   {:name "On Tertiary Container"  :color "#31111D"}
   {:name "Error"                  :color "#B3261E"}
   {:name "On Error"               :color "#FFFFFF"}
   {:name "Error Container"        :color "#F9DEDC"}
   {:name "On Error Container"     :color "#410E0B"}
   {:name "Background"             :color "#FEF7FF"}
   {:name "On Background"          :color "#1D1B20"}
   {:name "Surface"                :color "#FEF7FF"}
   {:name "On Surface"             :color "#1D1B20"}
   {:name "Surface Variant"        :color "#E7E0EC"}
   {:name "On Surface Variant"     :color "#49454F"}
   {:name "Outline"                :color "#79747E"}
   {:name "Outline Variant"        :color "#CAC4D0"}
   {:name "Surface Container"      :color "#F3EDF7"}
   {:name "Surface Container High" :color "#ECE6F0"}
   {:name "Surface Container Highest" :color "#E6E0E9"}
   {:name "Inverse Surface"        :color "#322F35"}
   {:name "Inverse On Surface"     :color "#F5EFF7"}
   {:name "Inverse Primary"        :color "#D0BCFF"}
   {:name "Scrim"                  :color "#000000"}
   {:name "Shadow"                 :color "#000000"}])

(def ^:private m3-dark-tokens
  [{:name "Primary"                :color "#D0BCFF"}
   {:name "On Primary"             :color "#381E72"}
   {:name "Primary Container"      :color "#4F378B"}
   {:name "On Primary Container"   :color "#EADDFF"}
   {:name "Secondary"              :color "#CCC2DC"}
   {:name "On Secondary"           :color "#332D41"}
   {:name "Secondary Container"    :color "#4A4458"}
   {:name "On Secondary Container" :color "#E8DEF8"}
   {:name "Tertiary"               :color "#EFB8C8"}
   {:name "On Tertiary"            :color "#492532"}
   {:name "Tertiary Container"     :color "#633B48"}
   {:name "On Tertiary Container"  :color "#FFD8E4"}
   {:name "Error"                  :color "#F2B8B5"}
   {:name "On Error"               :color "#601410"}
   {:name "Error Container"        :color "#8C1D18"}
   {:name "On Error Container"     :color "#F9DEDC"}
   {:name "Background"             :color "#141218"}
   {:name "On Background"          :color "#E6E0E9"}
   {:name "Surface"                :color "#141218"}
   {:name "On Surface"             :color "#E6E0E9"}
   {:name "Surface Variant"        :color "#49454F"}
   {:name "On Surface Variant"     :color "#CAC4D0"}
   {:name "Outline"                :color "#938F99"}
   {:name "Outline Variant"        :color "#49454F"}
   {:name "Surface Container"      :color "#211F26"}
   {:name "Surface Container High" :color "#2B2930"}
   {:name "Surface Container Highest" :color "#36343B"}
   {:name "Inverse Surface"        :color "#E6E0E9"}
   {:name "Inverse On Surface"     :color "#322F35"}
   {:name "Inverse Primary"        :color "#6750A4"}
   {:name "Scrim"                  :color "#000000"}
   {:name "Shadow"                 :color "#000000"}])

(defn token->library-color
  "Build a Penpot library-color map (passes `ctc/check-library-color`) for one
  M3 token. `:path` groups the token under Material 3/Light or Material 3/Dark."
  [{:keys [name color]} path]
  (-> {:id    (uuid/next)
       :name  name
       :path  path
       :color color}
      (dissoc nil)
      (ctc/check-library-color)))

(defn m3-token-colors
  "Return the full vector of M3 library-color maps (light + dark). Public so a
  future Material-theme generator can consume the same token set."
  []
  (into []
        cat
        [(map #(token->library-color % "Material 3/Light") m3-light-tokens)
         (map #(token->library-color % "Material 3/Dark")  m3-dark-tokens)]))

;; ── M3 component specs (DesignSpec frames/groups) ───────────────────────────
;;
;; Each M3 component is a `:group` spec whose children are the visual rects +
;; text labels. `design-gen/apply-design-spec` runs this through
;; `cds/spec->shape-tree` (the same pipeline the AI design generator uses),
;; so the kit reuses the EXISTING shape-creation path — no new builder here.
;;
;; Coordinates are absolute within the 'Material 3' board. M3 corner radii
;; (full = 999, large = 16, medium = 12, small = 8, extra-small = 4) and the
;; M3 typography scale (Label Large 14/500, Body Medium 14/400, Title Medium
;; 16/500, Headline Small 24/400) are honored. Colors reference the M3 roles
;; by hex value (the injected tokens use the same hex).

(defn- m3-fill
  ([hex] (m3-fill hex 1))
  ([hex opacity]
   [{:fill-color hex :fill-opacity opacity}]))

(def ^:private m3-board-width  1280)
(def ^:private m3-board-height 1180)

;; Helper: a centered text label inside a button-shaped rect.
(defn- m3-label
  [id x y w h content hex]
  {:id         id
   :type       "text"
   :name       "Label"
   :x          x
   :y          y
   :width      w
   :height     h
   :content    content
   :font-family "Roboto"
   :font-size   "14"
   :font-weight "500"
   :text-align  "center"
   :line-height (str h)
   :fills       (m3-fill hex)})

(defn- m3-button-group
  [id name x y w h label fill-hex label-hex r]
  {:id     id
   :type   "group"
   :name   name
   :x      x
   :y      y
   :width  w
   :height h
   :shapes [{:id     (str id "-bg")
             :type   "rect"
             :name   "Surface"
             :x      x
             :y      y
             :width  w
             :height h
             :r1     r :r2 r :r3 r :r4 r
             :fills  (m3-fill fill-hex)}
            (m3-label (str id "-label")
                      x y w h label label-hex)]})

(defn- m3-card-group
  [id name x y w h title-hex]
  {:id     id
   :type   "group"
   :name   name
   :x      x
   :y      y
   :width  w
   :height h
   :shapes [{:id     (str id "-bg")
             :type   "rect"
             :name   "Surface"
             :x      x
             :y      y
             :width  w
             :height h
             :r1     12 :r2 12 :r3 12 :r4 12
             :fills  (m3-fill "#FEF7FF")}
            {:id         (str id "-title")
             :type       "text"
             :name       "Title"
             :x          (+ x 16)
             :y          (+ y 16)
             :width      (- w 32)
             :height     24
             :content    "Title"
             :font-family "Roboto"
             :font-size   "16"
             :font-weight "500"
             :text-align  "left"
             :line-height "24"
             :fills       (m3-fill title-hex)}
            {:id         (str id "-body")
             :type       "text"
             :name       "Body"
             :x          (+ x 16)
             :y          (+ y 48)
             :width      (- w 32)
             :height     20
             :content    "Supporting text"
             :font-family "Roboto"
             :font-size   "14"
             :font-weight "400"
             :text-align  "left"
             :line-height "20"
             :fills       (m3-fill "#49454F")}]})

(defn- m3-text-field-group
  "Filled variant = surface fill with bottom-only corners (r3/r4 = 0).
  Outlined variant = plain surface with all corners r=4; the design-spec
  schema has no strokes, so the outline is implied by the component name
  rather than rendered as a border (a real border needs the post-commit
  shape model, out of scope for the additive kit)."
  [id name x y w h fill-hex stroke-hex label-hex outlined?]
  (let [radii (if outlined? [4 4 4 4] [4 4 0 0])
        bg    {:id     (str id "-bg")
               :type   "rect"
               :name   "Surface"
               :x      x
               :y      y
               :width  w
               :height h
               :r1     (nth radii 0)
               :r2     (nth radii 1)
               :r3     (nth radii 2)
               :r4     (nth radii 3)
               :fills  (m3-fill fill-hex)}
        label {:id         (str id "-label")
               :type       "text"
               :name       "Label"
               :x          (+ x 16)
               :y          (+ y 8)
               :width      (- w 32)
               :height     20
               :content    "Label"
               :font-family "Roboto"
               :font-size   "14"
               :font-weight "400"
               :text-align  "left"
               :line-height "20"
               :fills       (m3-fill label-hex)}]
    ;; stroke-hex is retained for API symmetry / future stroke support but
    ;; is not rendered under the additive no-stroke design-spec schema.
    {:id     id
     :type   "group"
     :name   name
     :x      x
     :y      y
     :width  w
     :height h
     :shapes (if outlined?
               ;; A subtle tint band at the bottom edge hints at the
               ;; outline weight without pretending to be a real stroke.
               [bg label
                {:id     (str id "-edge")
                 :type   "rect"
                 :name   "Edge"
                 :x      x
                 :y      (+ y h -1)
                 :width  w
                 :height 1
                 :fills  (m3-fill stroke-hex)}]
               [bg label])}))

(defn- m3-switch-group
  [id x y]
  (let [w 52 h 32]
    {:id     id
     :type   "group"
     :name   "Switch"
     :x      x
     :y      y
     :width  w
     :height h
     :shapes [{:id     (str id "-track")
               :type   "rect"
               :name   "Track"
               :x      x
               :y      y
               :width  w
               :height h
               :r1     16 :r2 16 :r3 16 :r4 16
               :fills  (m3-fill "#6750A4")}
              {:id     (str id "-thumb")
               :type   "circle"
               :name   "Thumb"
               :x      (+ x w -28)
               :y      (+ y 4)
               :width  24
               :height 24
               :fills  (m3-fill "#FFFFFF")}]}))

(defn- m3-checkbox-group
  [id x y]
  {:id     id
   :type   "group"
   :name   "Checkbox"
   :x      x
   :y      y
   :width  24
   :height 24
   :shapes [{:id     (str id "-box")
             :type   "rect"
             :name   "Box"
             :x      x
             :y      y
             :width  24
             :height 24
             :r1     4 :r2 4 :r3 4 :r4 4
             :fills  (m3-fill "#6750A4")}]})

(defn- m3-fab-group
  [id x y]
  (let [w 56 h 56]
    {:id     id
     :type   "group"
     :name   "FAB"
     :x      x
     :y      y
     :width  w
     :height h
     :shapes [{:id     (str id "-bg")
               :type   "rect"
               :name   "Surface"
               :x      x
               :y      y
               :width  w
               :height h
               :r1     16 :r2 16 :r3 16 :r4 16
               :fills  (m3-fill "#6750A4")}
              {:id     (str id "-plus")
               :type   "text"
               :name   "Icon"
               :x      x
               :y      y
               :width  w
               :height h
               :content    "+"
               :font-family "Roboto"
               :font-size   "24"
               :font-weight "400"
               :text-align  "center"
               :line-height (str h)
               :fills       (m3-fill "#FFFFFF")}]}))

(defn- m3-app-bar-group
  [id x y w]
  (let [h 64]
    {:id     id
     :type   "group"
     :name   "AppBar"
     :x      x
     :y      y
     :width  w
     :height h
     :shapes [{:id     (str id "-bg")
               :type   "rect"
               :name   "Surface"
               :x      x
               :y      y
               :width  w
               :height h
               :fills  (m3-fill "#FEF7FF")}
              {:id         (str id "-title")
               :type       "text"
               :name       "Title"
               :x          (+ x 16)
               :y          (+ y 20)
               :width      (- w 32)
               :height     24
               :content    "App Bar"
               :font-family "Roboto"
               :font-size   "16"
               :font-weight "500"
               :text-align  "left"
               :line-height "24"
               :fills       (m3-fill "#1D1B20")}]}))

(defn- m3-nav-bar-group
  [id x y w]
  (let [h 80]
    {:id     id
     :type   "group"
     :name   "NavigationBar"
     :x      x
     :y      y
     :width  w
     :height h
     :shapes [{:id     (str id "-bg")
               :type   "rect"
               :name   "Surface"
               :x      x
               :y      y
               :width  w
               :height h
               :fills  (m3-fill "#FEF7FF")}
              {:id         (str id "-label")
               :type       "text"
               :name       "Label"
               :x          x
               :y          (+ y 28)
               :width      w
               :height     24
               :content    "Home   Search   Profile"
               :font-family "Roboto"
               :font-size   "12"
               :font-weight "500"
               :text-align  "center"
               :line-height "24"
               :fills       (m3-fill "#6750A4")}]}))

(defn- m3-design-spec
  "Build the DesignSpec for the Material 3 board. One top-level frame
  'Material 3' containing one group per M3 component."
  []
  {:target "new-board"
   :frames
   [{:id     "m3-board"
     :name   "Material 3"
     :x      0
     :y      0
     :width  m3-board-width
     :height m3-board-height
     :fills  (m3-fill "#FEF7FF")
     :shapes
     [{:id         "m3-title"
       :type       "text"
       :name       "Title"
       :x          64
       :y          32
       :width      800
       :height     32
       :content    "Material 3 Design Kit"
       :font-family "Roboto"
       :font-size   "24"
       :font-weight "400"
       :text-align  "left"
       :line-height "32"
       :fills       (m3-fill "#1D1B20")}

      ;; Row 1 — buttons + FAB (y = 96).
      (m3-button-group "m3-btn-filled"     "Button"         64   96 200 40 "Button"         "#6750A4" "#FFFFFF" 20)
      (m3-button-group "m3-btn-outlined"   "OutlinedButton" 300  96 200 40 "Outlined"       "#FFFFFF" "#6750A4" 20)
      (m3-button-group "m3-btn-text"       "TextButton"     536  96 200 40 "Text"           "#FFFFFF" "#6750A4" 20)
      (m3-fab-group    "m3-fab"            772  96)

      ;; Row 2 — cards (y = 176).
      (m3-card-group    "m3-card"          "Card"           64  176 240 140 "#1D1B20")
      (m3-card-group    "m3-card-filled"   "FilledCard"     340 176 240 140 "#1D1B20")

      ;; Row 3 — text fields (y = 352).
      (m3-text-field-group "m3-tf-filled"   "TextField"        64  352 280 56 "#E7E0EC" "#000000" "#49454F" false)
      (m3-text-field-group "m3-tf-outlined" "OutlinedTextField" 380 352 280 56 "#FFFFFF" "#79747E" "#49454F" true)

      ;; Row 4 — switch + checkbox (y = 448).
      (m3-switch-group   "m3-switch"   64  448)
      (m3-checkbox-group "m3-checkbox" 160 448)

      ;; Row 5 — app bar + nav bar (y = 528 / 632).
      (m3-app-bar-group  "m3-appbar"   64  528 (- m3-board-width 128))
      (m3-nav-bar-group  "m3-navbar"   64  632 (- m3-board-width 128))]}]})

;; ── Injection event ─────────────────────────────────────────────────────────

(defn inject-material-kit
  "Add the Material 3 design kit to the current file in ONE undo transaction:
  the M3 color-role token set as library color styles + the M3 component
  board via `design-gen/apply-design-spec`. Idempotent — a file already
  stamped with plugin-data `:ovion \"m3-tokens\"` is left untouched and a
  friendly toast is shown. Nil-safe (no selected shapes / empty file are
  fine — `apply-design-spec` defaults to a new board)."
  []
  (ptk/reify ::inject-material-kit
    ptk/WatchEvent
    (watch [it state _]
      (let [file-data      (dsh/lookup-file-data state)
            existing-stamp (dm/get-in file-data [:plugin-data :ovion "m3-tokens"])
            page-id        (:current-page-id state)
            page           (dsh/lookup-page state)
            objects        (dsh/lookup-page-objects state)
            undo-id        (uuid/next)]
        (if (some? existing-stamp)
          ;; Idempotency guard — already injected, do nothing but inform.
          (rx/of (ntf/info (tr "workspace.assets.m3-already-added")))

          (let [;; Dedupe by name within the Material 3 group so a partial
                ;; prior inject (e.g. colors added but stamp missing) does
                ;; not create duplicate color styles. We check BOTH the
                ;; light and dark paths.
                existing-colors (or (:colors file-data) {})
                existing-names  (into #{}
                                      (keep (fn [c]
                                              (when (or (= (:path c) "Material 3/Light")
                                                        (= (:path c) "Material 3/Dark"))
                                                (:name c))))
                                      (vals existing-colors))
                tokens          (into []
                                     (filter (fn [c] (not (contains? existing-names (:name c)))))
                                     (m3-token-colors))

                ;; Color + plugin-data stamp changes. `with-file-data` +
                ;; `with-library-data` give the changes builder the file
                ;; context `pcb/set-plugin-data` and `apply-changes-local`
                ;; need; `with-page`/`with-objects` satisfy the page slots.
                changes0 (-> (pcb/empty-changes it page-id)
                             (pcb/with-page page)
                             (pcb/with-objects objects)
                             (pcb/with-library-data file-data)
                             (pcb/with-file-data file-data))
                changes  (reduce pcb/add-color changes0 tokens)
                changes  (pcb/set-plugin-data changes :ovion "m3-tokens" "true")

                spec     (m3-design-spec)]

            (rx/of (dwu/start-undo-transaction undo-id)
                   (dch/commit-changes changes)
                   ;; Reuses the EXISTING spec→shapes pipeline. Its own
                   ;; inner start/commit undo transaction nests inside ours
                   ;; (dwu transactions accumulate while pending is
                   ;; non-empty), so the whole kit lands as one undo entry.
                   (design-gen/apply-design-spec {:spec spec :select? false})
                   (dwu/commit-undo-transaction undo-id))))))))