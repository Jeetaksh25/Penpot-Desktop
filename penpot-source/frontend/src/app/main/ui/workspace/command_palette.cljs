;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.command-palette
  "Figma-parity full Command Bar (gap #47, P2.22).

  Evolves the original curated 9-command quick-action overlay into a full
  Command Bar (Cmd+K) that indexes EVERY workspace shortcut with fuzzy
  search, plus two component modes:

    :commands         — default. Fuzzy-search the whole workspace shortcut
                        registry (tools, menu actions, view toggles,
                        alignment, zoom, …). Enter runs the highlighted
                        command. create-component-variant lives here as an
                        entry (it used to own Cmd+K).

    :insert-component — opened by pressing S while the Command Bar is in
                        command mode with an empty query. Fuzzy-searches
                        every component in the current file + linked
                        libraries. Enter instantiates the highlighted
                        component at the viewport center.

    :swap-instance    — opened by the Cmd+Opt+R global shortcut
                        (`open-swap!`, wired in shortcuts.cljs). Replaces
                        the selected instance with the chosen component
                        (`dwl/component-multi-swap`). Disabled with a hint
                        when no single instance is selected.

  The EXACT look of the original palette is preserved — same overlay,
  container, input and list classes, same Up/Down/Enter/Esc behaviour. The
  only visible difference is the placeholder text per mode and a larger
  list (more entries). It is still mounted only under the
  :command-palette layout flag (see viewport.cljs), so byte-identical-off
  by default."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.command-bar :as cb]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.shortcuts :as ws]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.workspace.viewport.viewport-ref :as uv]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; ---------------------------------------------------------------------------
;; Mode plumbing — see app.main.data.workspace.command-bar (cb). The mode
;; atom + `open-swap!` live there to avoid a circular require with the
;; shortcuts registry (which the palette indexes). The Command Bar reads
;; `cb/requested-mode` on mount, then resets it so a plain Cmd+K always
;; opens in :commands.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Command registry (index every workspace shortcut)
;; ---------------------------------------------------------------------------

(defn- humanize
  "Turn a shortcut id keyword into a readable label
  (e.g. :create-component-variant -> \"Create component variant\")."
  [kw]
  (let [s (-> (name kw) (str/replace "-" " "))]
    (if (str/blank? s) s
        (dm/str (str/upper (subs s 0 1)) (subs s 1)))))

(def ^:private command-registry
  "Flat vector of {:id :label :run} for every workspace shortcut that has
  an executable `:fn`. The `:command-palette` entry itself is excluded
  (running it from inside the palette would just close it)."
  (into []
        (for [[id entry] ws/shortcuts
              :when (and (map? entry)
                         (fn? (:fn entry))
                         (not= id :command-palette))]
          {:id id :label (humanize id) :run (:fn entry)})))

;; ---------------------------------------------------------------------------
;; Component index (for insert / swap modes)
;; ---------------------------------------------------------------------------

(defn- build-components
  "Flatten the current file + linked libraries into a vector of
  {:id :file-id :name :file-name :full-name} for every non-deleted
  component. `libraries` is `refs/libraries` ({file-id -> file})."
  [libraries]
  (into []
        (for [[file-id file] libraries
              :let [file-data (:data file)
                    file-name (or (:name file) "")
                    components (:components file-data)]
              [comp-id comp] (or components {})
              :when (and (map? comp) (not (true? (:deleted comp))))]
          (let [nm   (or (:name comp) "Component")
                path (:path comp)
                full (if (str/blank? path) nm (dm/str path " / " nm))]
            {:id comp-id
             :file-id file-id
             :name nm
             :file-name file-name
             :full-name full}))))

;; ---------------------------------------------------------------------------
;; Fuzzy search (subsequence matcher + scorer, no dependency)
;; ---------------------------------------------------------------------------

(defn- fuzzy-score
  "Return a numeric score when `query` is a case-insensitive subsequence
  of `label` (lower = better match), else nil. Earlier matches and longer
  contiguous streaks reduce the score; matching the whole label is best."
  [query label]
  (let [q (str/lower (str query))
        l (str/lower (str label))]
    (if (str/blank? q)
      0
      (loop [qi 0 li 0 score 0 streak 0 best 0]
        (cond
          (= qi (count q))   (- score (* best 10) (if (= li (count l)) 5 0))
          (>= li (count l))  nil
          :else              (let [qc (get q qi) lc (get l li)]
                              (if (= qc lc)
                                (recur (inc qi) (inc li) (+ score li)
                                       (inc streak) (max best (inc streak)))
                                (recur qi (inc li) score 0 best))))))))

(defn- fuzzy-filter
  "Filter `items` by `query` against `(getf item)` and sort best-first,
  alphabetical tiebreak. Returns a vector. Empty query -> `items` unchanged."
  [items query getf]
  (if (str/blank? query)
    (into [] items)
    (let [q (str/lower (str query))]
      (->> (for [item items
                 :let [s (fuzzy-score q (getf item))]
                 :when (some? s)]
             [s item])
           (sort-by (fn [[s item]] [s (str/lower (str (getf item)))]))
           (mapv second)))))

;; ---------------------------------------------------------------------------
;; Insert action (instantiate at the viewport center)
;; ---------------------------------------------------------------------------

(defn- instantiate-at-center!
  "Instantiate `component-id` (from `file-id`) at the canvas-coordinate
  center of the workspace viewport."
  [file-id component-id]
  (when-let [vp-ref @uv/viewport-ref]
    (let [rect (dom/get-bounding-rect vp-ref)
          cx (+ (:left rect) (/ (:width rect) 2))
          cy (+ (:top rect) (/ (:height rect) 2))
          canvas-pt (uv/point->viewport (gpt/point cx cy))]
      (when (some? canvas-pt)
        (st/emit! (dwl/instantiate-component file-id component-id canvas-pt))))))

;; ---------------------------------------------------------------------------
;; Close helper
;; ---------------------------------------------------------------------------

(defn- close!
  []
  (st/emit! (dw/toggle-layout-flag :command-palette)))

;; ---------------------------------------------------------------------------
;; Component
;; ---------------------------------------------------------------------------

(mf/defc command-palette*
  {:wrap [mf/memo]}
  [_props]
  (let [;; Capture the requested mode once on mount, then reset the atom
        ;; so a plain Cmd+K always opens in :commands.
        initial-mode (cb/requested-mode)
        mode*    (mf/use-state initial-mode)
        mode     (deref mode*)
        query*   (mf/use-state "")
        query    (deref query*)
        index*   (mf/use-state 0)
        index    (deref index*)
        input-ref (mf/use-ref nil)

        libraries (mf/deref refs/libraries)
        objects   (mf/deref refs/workspace-page-objects)
        selected-ids (mf/deref refs/selected-shapes)
        selected  (mf/with-memo [objects selected-ids]
                    (->> selected-ids (keep #(get objects %)) vec))

        components
        (mf/with-memo [libraries]
          (build-components libraries))

        ;; Swap needs a single selected component instance to replace.
        swap-target
        (mf/with-memo [selected mode]
          (when (= :swap-instance mode)
            (let [inst (filter #(some? (:component-id %)) selected)]
              (when (= 1 (count inst)) (first inst)))))

        ;; Items shown in the list for the current mode.
        items
        (mf/with-memo [mode query command-registry components]
          (case mode
            :commands         (fuzzy-filter command-registry query :label)
            :insert-component (fuzzy-filter components query :full-name)
            :swap-instance    (fuzzy-filter components query :full-name)
            []))

        n (count items)

        placeholder
        (case mode
          :commands         (tr "workspace.command-palette.placeholder")
          :insert-component (tr "workspace.command-palette.placeholder.insert")
          :swap-instance    (tr "workspace.command-palette.placeholder.swap"))

        on-query-change
        (mf/use-fn
         (fn [event]
           (let [value (dom/get-value (dom/get-target event))]
             (reset! query* value)
             (reset! index* 0))))

        switch-mode
        (mf/use-fn
         (fn [new-mode]
           (reset! mode* new-mode)
           (reset! query* "")
           (reset! index* 0)
           (some-> (mf/ref-val input-ref) dom/focus!)))

        run-item
        (mf/use-fn
         (mf/deps mode items index swap-target)
         (fn [& [i]]
           (let [i (or i index)]
             (case mode
               :commands
               (when-let [cmd (nth items i nil)]
                 (when (fn? (:run cmd)) ((:run cmd)))
                 (close!))
               :insert-component
               (when-let [comp (nth items i nil)]
                 (instantiate-at-center! (:file-id comp) (:id comp))
                 (close!))
               :swap-instance
               (when-let [comp (nth items i nil)]
                 (when (some? swap-target)
                   (st/emit! (dwl/component-multi-swap
                              [swap-target] (:file-id comp) (:id comp)))
                   (close!)))))))

        on-key-down
        (mf/use-fn
         (mf/deps mode query items index n)
         (fn [event]
           (cond
             (kbd/esc? event)        (do (dom/prevent-default event) (close!))
             (kbd/enter? event)      (do (dom/prevent-default event) (run-item))
             (kbd/down-arrow? event) (do (dom/prevent-default event)
                                         (reset! index* (mod (inc index) (max 1 n))))
             (kbd/up-arrow? event)  (do (dom/prevent-default event)
                                         (reset! index* (mod (dec index) (max 1 n))))
             ;; S opens insert-component mode when in :commands with an empty
             ;; query and no modifiers (so typing into a query still works).
             (and (= mode :commands)
                  (str/blank? query)
                  (= "s" (str/lower (.-key event)))
                  (not (or (kbd/mod? event) (kbd/alt? event) (kbd/shift? event))))
             (do (dom/prevent-default event) (switch-mode :insert-component))
             :else nil)))

        on-item-click
        (mf/use-fn
         (mf/deps mode items selected)
         (fn [event]
           (let [idx (-> (dom/get-current-target event)
                         (dom/get-data "idx")
                         (d/read-string))]
             (reset! index* idx)
             (run-item idx))))]

    (mf/with-effect []
      ;; Autofocus the input on mount; reset the requested-mode atom so the
      ;; next plain open defaults to :commands.
      (cb/reset-mode!)
      (some-> (mf/ref-val input-ref) dom/focus!))

    [:div {:class (stl/css :command-palette-overlay)
           :on-pointer-down (fn [event]
                              (when (= (dom/get-target event)
                                       (dom/get-current-target event))
                                (close!)))}
     [:div {:class (stl/css :command-palette)
            :on-key-down on-key-down}
      [:input {:class (stl/css :command-palette-input)
               :type "text"
               :ref input-ref
               :placeholder placeholder
               :value query
               :on-change on-query-change}]
      [:ul {:class (stl/css :command-palette-list)}
       (cond
         (and (= mode :swap-instance) (nil? swap-target))
         [:li {:class (stl/css :command-palette-item)
               :aria-disabled true}
          (tr "workspace.command-palette.swap.no-selection")]

         (zero? n)
         [:li {:class (stl/css :command-palette-item)
               :aria-disabled true}
          (case mode
            :commands         (tr "workspace.command-palette.empty")
            :insert-component (tr "workspace.command-palette.insert.empty")
            :swap-instance    (tr "workspace.command-palette.swap.empty")
            "")]

         :else
         (for [idx (range n)]
           (let [item (nth items idx)
                 label (case mode
                         :commands         (:label item)
                         :insert-component (:full-name item)
                         :swap-instance    (:full-name item)
                         "")]
             [:li {:key (str idx "-" (or (:id item) idx))
                   :data-idx (str idx)
                   :class (stl/css-case :command-palette-item true
                                       :selected (= idx index))
                   :on-click on-item-click}
              label])))]]]))