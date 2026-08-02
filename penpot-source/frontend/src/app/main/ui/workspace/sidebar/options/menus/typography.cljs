;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.typography
  (:require-macros [app.main.style :as stl])
  (:require
   ["react-virtualized" :as rvt]
   ["@tauri-apps/api/core" :refer [invoke]]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.media :as cm]
   [app.common.types.text :as txt]
   [app.main.constants :refer [max-input-length]]
   [app.main.data.common :as dcm]
   [app.main.data.fonts :as fts]
   [app.main.data.notifications :as ntf]
   [app.main.data.shortcuts :as dsc]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.undo :as dwu]
   [app.main.features :as features]
   [app.main.fonts :as fonts]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.editable-select :refer [editable-select]]
   [app.main.ui.components.file-uploader :refer [file-uploader]]
   [app.main.ui.components.numeric-input :as deprecated-input]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.components.search-bar :refer [search-bar*]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.strings :as ust]
   [app.util.timers :as tm]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [goog.events :as events]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(defn- attr->string [value]
  (if (= value :multiple)
    ""
    (ust/format-precision value 2)))

(defn- get-next-font
  [{:keys [id] :as current} fonts]
  (if (seq fonts)
    (let [index (d/index-of-pred fonts #(= (:id %) id))
          index (or index -1)
          next  (ex/ignoring (nth fonts (inc index)))]
      (or next (first fonts)))
    current))

(defn- get-prev-font
  [{:keys [id] :as current} fonts]
  (if (seq fonts)
    (let [index (d/index-of-pred fonts #(= (:id %) id))
          next  (ex/ignoring (nth fonts (dec index)))]
      (or next (peek fonts)))
    current))

(mf/defc font-item*
  {::mf/wrap [mf/memo]}
  [{:keys [font is-current on-click style]}]
  (let [item-ref (mf/use-ref)
        on-click (mf/use-fn (mf/deps font) #(on-click font))

        ;; Feature 1 — optional offline download for Google fonts. Pre-caches
        ;; the family's CSS2 + every variant + the menu-preview TTF into the
        ;; app-data fonts cache via the `fonts_download_family` Tauri command,
        ;; so the family then renders + exports fully offline. The proxy serves
        ;; the cached files at the same URLs, so no online/offline branching in
        ;; the SPA. The button is rendered only for Google fonts (Feature 1
        ;; Phase 3 "download" affordance); the click is stopped so it doesn't
        ;; also select the font.
        google?    (= (:backend font) :google)
        on-download (mf/use-fn
                     (mf/deps font)
                     (fn [event]
                       (dom/stop-propagation event)
                       (let [name (:name font)]
                         (-> (invoke "fonts_download_family"
                                     #js {:query   (fonts/gfont-css-query font)
                                          :menuUrl (:menu font)})
                             (.then (fn [_]
                                      (st/emit!
                                       (ntf/info
                                        (dm/str "Cached '" name "' for offline use.")))))
                             (.catch (fn [_]
                                       (st/emit!
                                        (ntf/error
                                         (dm/str "Couldn't cache '" name "' for offline.")))))))))]

    (mf/use-effect
     (mf/deps is-current)
     (fn []
       (when is-current
         (let [element (mf/ref-val item-ref)]
           (when-not (dom/is-in-viewport? element)
             (dom/scroll-into-view! element))))))

    ;; Feature 1 — render the family name in its own typeface (like word
    ;; editors). For Google fonts we inject a one-file "menu" @font-face
    ;; (lightweight); for custom uploaded fonts we ensure the family is loaded.
    ;; Builtin fonts (e.g. Source Sans Pro) are already loaded as the UI font.
    (mf/use-effect
     (mf/deps (:id font))
     (fn []
       (let [backend (:backend font)]
         (cond
           (= :google backend) (fonts/ensure-gfont-preview! (:id font))
           (= :custom backend) (fonts/ensure-loaded! (:id font))
           :else nil))))

    [:div {:class (stl/css :font-wrapper)
           :style style
           :ref item-ref
           :on-click on-click}
     [:div {:class  (stl/css-case :font-item true
                                  :selected is-current)}
      [:span {:class (stl/css :font-item-label)
              :style {:font-family (fonts/css-font-family (:family font))}} (:name font)]
      (when google?
        [:> icon-button* {:variant "action"
                           :aria-label "Download family for offline use"
                           :icon i/download
                           :class (stl/css :font-download-btn)
                           :on-click on-download}])
      (when is-current
        [:> icon* {:icon-id i/tick
                   :size "s"}])]]))

(declare row-renderer)

(defn filter-fonts
  [{:keys [term backends]} fonts]
  (let [term (str/lower term)
        xform (cond-> (map identity)
                (seq term)
                (comp (filter #(str/includes? (str/lower (:name %)) term)))

                (seq backends)
                (comp (filter #(contains? backends (:backend %)))))]
    (into [] xform fonts)))

;; Same accept string the dashboard Fonts page uses (see
;; `app.main.ui.dashboard.fonts/accept-font-types`). The extra `,ttf,...`
;; entries work around a Chromium `<input accept>` quirk so all common font
;; formats are pickable.
(def ^:private accept-font-types
  (str (str/join "," cm/font-types)
       ",.ttf,application/font-woff,.woff,.woff2,.otf"))

;; The team's already-installed custom fonts (item-id -> font, each carrying a
;; :font-id), read from the same store path the dashboard Fonts page uses
;; (`dashboard.fonts/ref:fonts`). We pass this to `fts/merge-and-group-fonts`
;; so importing an extra variant of an existing family reuses that family's
;; font-id instead of creating a duplicate family.
(def ^:private ref:installed-fonts
  (l/derived :fonts st/state))

;; In-project custom-font import, surfaced directly in the font selector so
;; the user doesn't have to leave the workspace for the dashboard Fonts page.
;; Reuses the proven dashboard pipeline: `fts/process-upload` (opentype.js
;; parse + per-variant grouping) -> `fts/merge-and-group-fonts` (assigns
;; :font-id, reusing an existing family's id or a fresh uuid per new family)
;; -> `fts/upload-font-variant` (chunked blob upload -> `:create-font-variant`)
;; -> per variant `fts/add-font` (progressive picker update), then a single
;; `fts/fetch-fonts` once all variants land. The `fetch-fonts` call is
;; CRITICAL: it is the only path that runs `fonts/register! :custom`, which
;; refreshes the picker's `fonts/fonts` db so the newly imported family shows
;; up immediately. `fts/add-font` alone updates state :fonts but does NOT
;; refresh the picker. `merge-and-group-fonts` is REQUIRED because
;; process-upload items carry no :font-id and :create-font-variant's backend
;; schema requires font-id as a UUID.
(mf/defc font-import-affordance*
  {::mf/private true}
  []
  (let [team-id   (mf/use-ctx ctx/current-team-id)
        read-only (mf/use-ctx ctx/workspace-read-only?)
        installed-fonts (mf/deref ref:installed-fonts)

        importing* (mf/use-state false)
        importing  (deref importing*)
        input-ref  (mf/use-ref)

        on-click
        (mf/use-fn
         (fn [event]
           ;; Stop propagation so the click doesn't bubble into the
           ;; selector's keydown/focus handling or select a font row.
           (dom/stop-propagation event)
           (some-> (mf/ref-val input-ref) dom/click)))

        upload-one
        (mf/use-fn
         (mf/deps team-id)
         ;; Upload one processed item. Emits [item font] on success, or nil
         ;; on a per-item failure (after surfacing an error toast). nil keeps
         ;; the outer stream alive so the completion counter still decrements.
         (fn [item]
           (->> (fts/upload-font-variant item)
                ;; Mirror the dashboard: a small floor so the
                ;; uploading->uploaded transition is visible, not a flicker
                ;; on fast machines.
                (rx/delay-at-least 2000)
                (rx/map (fn [font] [item font]))
                (rx/catch
                 (fn [cause]
                   (js/console.error "Font import failed" cause)
                   (st/emit! (ntf/error
                              (tr "errors.bad-font"
                                  (or (first (:names item)) "font"))))
                   (rx/of nil))))))

        on-selected
        (mf/use-fn
         (mf/deps upload-one team-id installed-fonts)
         (fn [blobs]
           (if (or (nil? team-id) (empty? blobs))
             nil
             (let [remaining (volatile! 0)
                   on-item
                   (fn [pair]
                     (when-let [[_item font] pair]
                       ;; Per-variant: update local :fonts state so the picker
                       ;; list updates progressively as each variant lands.
                       (st/emit! (fts/add-font font)))
                     (when (zero? (vswap! remaining dec))
                       ;; CRITICAL: refresh the picker ONCE after every variant
                       ;; has uploaded. `fetch-fonts` -> `fonts-fetched` ->
                       ;; `fonts/register! :custom` is the only path that
                       ;; refreshes the picker's `fonts/fonts` db (add-font
                       ;; alone does not). Firing it once at the end — not per
                       ;; variant — avoids N redundant :get-font-variants RPCs
                       ;; and N fonts/register! re-registrations.
                       (st/emit! (fts/fetch-fonts team-id))
                       (reset! importing* false)))]
               (reset! importing* true)
               (->> (fts/process-upload blobs team-id)
                    (rx/subs!
                     (fn [result]
                       ;; Assign :font-id BEFORE uploading. process-upload
                       ;; items carry no :font-id, and :create-font-variant's
                       ;; backend schema requires font-id as a UUID, so without
                       ;; this every upload fails validation and no font is
                       ;; ever imported. merge-and-group-fonts reuses an
                       ;; existing family's id (from installed-fonts) or
                       ;; generates a single fresh uuid shared across all
                       ;; variants of a new family — mirroring the dashboard
                       ;; Fonts page.
                       (let [merged (fts/merge-and-group-fonts {} installed-fonts result)
                             items  (into [] (vals merged))]
                         (if (empty? items)
                           ;; process-upload already surfaced a toast for any
                           ;; unreadable files (its internal `errors`
                           ;; subscription), so — mirroring the dashboard —
                           ;; emit nothing here and the user sees a single
                           ;; notification instead of two.
                           (reset! importing* false)
                           (do
                             (vreset! remaining (count items))
                             (->> (rx/from items)
                                  (rx/mapcat upload-one)
                                  (rx/subs!
                                   on-item
                                   (fn [cause]
                                     (js/console.error "Font import stream error" cause)
                                     (reset! importing* false))))))))
                     (fn [error]
                       (js/console.error "Font process-upload error" error)
                       (reset! importing* false)
                       (st/emit! (ntf/error (tr "errors.generic"))))))))))]
    (when (and team-id (not read-only))
      [:div {:class (stl/css :font-import-affordance)}
       [:button {:type "button"
                 :class (stl/css-case :font-import-btn true
                                      :is-importing importing)
                 :disabled importing
                 :title (tr "labels.add-custom-font")
                 :aria-label (tr "labels.add-custom-font")
                 :on-click on-click}
        (if importing
          [:span {:class (stl/css :font-import-spinner)}]
          [:> icon* {:icon-id i/add :size "s"}])]
       ;; Hidden file input — zero new Tauri permissions (HTML file dialog).
       [:& file-uploader {:input-id "font-selector-import"
                          :accept accept-font-types
                          :multi true
                          :ref input-ref
                          :on-selected on-selected}]])))

(mf/defc font-selector*
  [{:keys [on-select on-close current-font show-recent full-size]}]
  (let [selected     (mf/use-state current-font)
        state*       (mf/use-state
                      #(do {:term "" :backends #{}}))
        state        (deref state*)

        flist        (mf/use-ref)
        input        (mf/use-ref)

        fonts        (mf/deref fonts/fonts)
        fonts        (mf/with-memo [state fonts]
                       (filter-fonts state fonts))

        recent-fonts (mf/deref refs/recent-fonts)
        recent-fonts (mf/with-memo [state recent-fonts]
                       (filter-fonts state recent-fonts))


        full-size?   (boolean (and full-size show-recent))

        select-next
        (mf/use-fn
         (mf/deps fonts)
         (fn [event]
           (dom/stop-propagation event)
           (dom/prevent-default event)
           (swap! selected get-next-font fonts)))

        select-prev
        (mf/use-fn
         (mf/deps fonts)
         (fn [event]
           (dom/stop-propagation event)
           (dom/prevent-default event)
           (swap! selected get-prev-font fonts)))

        on-key-down
        (mf/use-fn
         (mf/deps fonts)
         (fn [event]
           (cond
             (kbd/up-arrow? event)   (select-prev event)
             (kbd/down-arrow? event) (select-next event)
             (kbd/esc? event)        (on-close)
             (kbd/enter? event)      (on-close)
             :else                   (dom/focus! (mf/ref-val input)))))

        on-filter-change
        (mf/use-fn
         (fn [event]
           (swap! state* assoc :term event)))

        on-select-and-close
        (mf/use-fn
         (mf/deps on-select on-close)
         (fn [font]
           (on-select font)
           (on-close)))]

    (mf/with-effect [fonts]
      (let [key (events/listen js/document "keydown" on-key-down)]
        #(events/unlistenByKey key)))

    (mf/with-effect [@selected]
      (when-let [inst (mf/ref-val flist)]
        (when-let [index (:index @selected)]
          (.scrollToRow ^js inst index))))

    (mf/with-effect [@selected]
      (on-select @selected))

    (mf/with-effect []
      (st/emit! (dsc/push-shortcuts :typography {}))
      (fn []
        (st/emit! (dsc/pop-shortcuts :typography))))

    (mf/with-effect []
      (let [index  (d/index-of-pred fonts #(= (:id %) (:id current-font)))
            inst   (mf/ref-val flist)]
        (tm/schedule
         #(let [offset (.getOffsetForRow ^js inst #js {:alignment "center" :index index})]
            (.scrollToPosition ^js inst offset)))))

    [:div {:class [(stl/css-case :font-selector true
                                 :fonts-on-modal (not full-size?))]}
     [:div {:class (stl/css-case :font-selector-dropdown true
                                 :font-selector-dropdown-full-size full-size?)}
      [:div {:class (stl/css :header)}
       [:div {:class (stl/css :font-selector-header-row)}
        [:> search-bar* {:on-change on-filter-change
                         :value (:term state)
                         :auto-focus true
                         :placeholder (tr "workspace.options.search-font")}]
        [:> font-import-affordance*]]
       (when (and recent-fonts show-recent)
         [:section {:class (stl/css :show-recent)}
          [:p {:class (stl/css :header-title)} (tr "workspace.options.recent-fonts")]
          (for [[idx font] (d/enumerate recent-fonts)]
            [:> font-item* {:key (dm/str "font-" idx)
                            :font font
                            :style {}
                            :on-click on-select-and-close
                            :is-current (= (:id font) (:id @selected))}])])]

      [:div {:class (stl/css-case :fonts-list true
                                  :fonts-list-full-size full-size?)}
       [:> rvt/AutoSizer {}
        (fn [props]
          (let [width  (unchecked-get props "width")
                height (unchecked-get props "height")
                render #(row-renderer fonts @selected on-select-and-close %)]
            (mf/html
             [:> rvt/List #js {:height height
                               :ref flist
                               :width width
                               :rowCount (count fonts)
                               :rowHeight 36
                               :rowRenderer render}])))]]]]))

(defn row-renderer
  [fonts selected on-select props]
  (let [index (unchecked-get props "index")
        key   (unchecked-get props "key")
        style (unchecked-get props "style")
        font  (nth fonts index)]
    (mf/html
     [:> font-item* {:key key
                     :font font
                     :style style
                     :on-click on-select
                     :is-current (= (:id font) (:id selected))}])))

(mf/defc font-options*
  [{:keys [values on-change on-blur show-recent full-size-selector]}]
  (let [{:keys [font-id font-size font-variant-id]} values

        font-id         (or font-id (:font-id txt/default-typography))
        font-size       (or font-size (:font-size txt/default-typography))
        font-variant-id (or font-variant-id (:font-variant-id txt/default-typography))

        fonts           (mf/deref fonts/fontsdb)
        font            (get fonts font-id)

        last-font       (mf/use-ref nil)

        open-selector?  (mf/use-state false)

        change-font
        (mf/use-fn
         (mf/deps on-change fonts)
         (fn [new-font-id]
           (let [{:keys [family] :as font} (get fonts new-font-id)
                 {:keys [id name weight style]} (fonts/get-default-variant font)]
             (on-change {:font-id new-font-id
                         :font-family family
                         :font-variant-id (or id name)
                         :font-weight weight
                         :font-style style})
             (mf/set-ref-val! last-font font))))

        on-font-size-change
        (mf/use-fn
         (mf/deps on-change)
         (fn [new-font-size]
           (when-not (str/empty? new-font-size)
             (on-change {:font-size (str new-font-size)}))))

        on-font-variant-change
        (mf/use-fn
         (mf/deps font on-change)
         (fn [new-variant-id]
           (let [variant (d/seek #(= new-variant-id (:id %)) (:variants font))]
             (when-not (nil? variant)
               (on-change {:font-id (:id font)
                           :font-family (:family font)
                           :font-variant-id new-variant-id
                           :font-weight (:weight variant)
                           :font-style (:style variant)}))
             ;; NOTE: the select component we are using does not fire on-blur event
             ;; so we need to call on-blur manually
             (when (some? on-blur)
               (on-blur)))))

        on-font-select
        (mf/use-fn
         (mf/deps change-font)
         (fn [font*]
           (when (not= font font*)
             (change-font (:id font*)))

           (when (some? on-blur)
             (on-blur))))

        on-font-selector-close
        (mf/use-fn
         (fn []
           (reset! open-selector? false)
           (when (some? on-blur)
             (on-blur))
           (when (mf/ref-val last-font)
             (st/emit! (fts/add-recent-font (mf/ref-val last-font))))))]

    [:*
     (when @open-selector?
       [:> font-selector*
        {:current-font font
         :on-close on-font-selector-close
         :on-select on-font-select
         :full-size full-size-selector
         :origin "right-sidebar"
         :show-recent show-recent}])

     [:div {:class (stl/css :font-option)
            :title (tr "inspect.attributes.typography.font-family")
            :on-click #(reset! open-selector? true)}
      (cond
        (or (= :multiple font-id) (= "mixed" font-id))
        [:*
         [:span {:class (stl/css :font-option-name :font-family-mixed)}
          (tr "inspect.attributes.typography.mixed-font-family")]
         [:> icon* {:icon-id i/arrow-down
                    :class (stl/css :dropdown-icon)
                    :size "s"}]]

        (some? font)
        [:*
         [:span {:class (stl/css :font-option-name)}
          (:name font)]
         [:> icon* {:icon-id i/arrow-down
                    :class (stl/css :dropdown-icon)
                    :size "s"}]]

        :else
        (tr "dashboard.fonts.deleted-placeholder"))]

     [:div {:class (stl/css :font-modifiers)}
      [:div {:class (stl/css :font-size-options)
             :title (tr "inspect.attributes.typography.font-size")}
       (let [size-options [8 9 10 11 12 14 16 18 24 36 48 72]
             size-options (if (= font-size :multiple) (into [""] size-options) size-options)]
         [:& editable-select
          {:value (if (= font-size :multiple) :multiple (attr->string font-size))
           :class (stl/css :font-size-select)
           :aria-label (tr "inspect.attributes.typography.font-size")
           :input-class (stl/css :numeric-input)
           :options size-options
           :type "number"
           :placeholder (tr "settings.multiple")
           :min 3
           :max 1000
           :on-change on-font-size-change
           :on-blur on-blur}])]

      [:div {:class (stl/css :font-variant-options)
             :title (tr "inspect.attributes.typography.font-style")}
       (let [basic-variant-options (->> (:variants font)
                                        (map (fn [variant]
                                               {:value (:id variant)
                                                :key (pr-str variant)
                                                :label (:name variant)})))
             ;; When the selection mixes variants we prepend a "--" entry: it is
             ;; shown as the collapsed value (nothing single is selected) while
             ;; the real variants of the resolved font are still listed below it.
             variant-options (if (or (= font-variant-id :multiple) (= font-variant-id "mixed"))
                               (conj basic-variant-options
                                     {:value ""
                                      :key :multiple-variants
                                      :label "--"})
                               basic-variant-options)
             font-variant-value (attr->string font-variant-id)
             font-variant-value (if (= font-variant-value "mixed") "" font-variant-value)]

         ;;  TODO Add disabled mode
         [:& select
          {:class (stl/css :font-variant-select)
           :default-value font-variant-value
           :options variant-options
           :on-change on-font-variant-change
           :on-blur on-blur}])]]]))

(mf/defc spacing-options*
  [{:keys [values on-change on-blur advanced-spacing?]}]
  (let [{:keys [line-height
                line-height-mode
                letter-spacing
                paragraph-spacing
                paragraph-indent]} values
        line-height (or line-height "1.2")
        letter-spacing (or letter-spacing "0")
        ;; Feature 14 — line-height mode. Absent / "auto" = legacy unitless
        ;; multiplier (existing behavior); "percent" = value/100; "px" = px.
        line-height-mode (if (= line-height-mode :multiple)
                           ""
                           (or (d/name line-height-mode) "auto"))
        handle-change
        (fn [value attr]
          (on-change {attr (ust/format-precision value 2)}))

        on-mode-change
        (mf/use-fn
         (mf/deps on-change on-blur)
         (fn [value]
           (on-change {:line-height-mode (if (= value "auto") nil value)})
           (when (some? on-blur) (on-blur))))

        mode-options
        (mf/with-memo []
          [{:value "auto"
            :key "lh-mode-auto"
            :label (tr "workspace.options.text-options.line-height-mode.auto")}
           {:value "percent"
            :key "lh-mode-percent"
            :label (tr "workspace.options.text-options.line-height-mode.percent")}
           {:value "px"
            :key "lh-mode-px"
            :label (tr "workspace.options.text-options.line-height-mode.px")}])]

    [:div {:class (stl/css :spacing-options)}
     [:div {:class (stl/css :line-height)
            :title (tr "inspect.attributes.typography.line-height")}
      [:span {:class (stl/css :icon)
              :alt (tr "workspace.options.text-options.line-height")}
       deprecated-icon/text-lineheight]
      [:> deprecated-input/numeric-input*
       {:min -200
        :max 200
        :step 0.1
        :default-value "1.2"
        :class (stl/css :line-height-input)
        :aria-label (tr "inspect.attributes.typography.line-height")
        :value (attr->string line-height)
        :placeholder (if (= :multiple line-height) (tr "settings.multiple") "--")
        :is-nillable (= :multiple line-height)
        :on-change #(handle-change % :line-height)
        :on-blur on-blur}]
      ;; Feature 14 — line-height mode selector (Auto / Percent / Pixels).
      ;; Reuses the font-variant select styling; additive control, default
      ;; "auto" preserves the legacy unitless-multiplier behavior. Only shown
      ;; for text shapes (advanced-spacing?), not the typography asset editor,
      ;; since these attrs are not part of the typography schema.
      (when advanced-spacing?
        [:& select
         {:class (stl/css :font-variant-select)
          :default-value line-height-mode
          :options mode-options
          :on-change on-mode-change
          :on-blur on-blur}])]

     [:div {:class (stl/css :letter-spacing)
            :title (tr "inspect.attributes.typography.letter-spacing")}
      [:span
       {:class (stl/css :icon)
        :alt (tr "workspace.options.text-options.letter-spacing")}
       deprecated-icon/text-letterspacing]
      [:> deprecated-input/numeric-input*
       {:min -200
        :max 200
        :step 0.1
        :default-value "0"
        :class (stl/css :letter-spacing-input)
        :aria-label (tr "inspect.attributes.typography.letter-spacing")
        :value (attr->string letter-spacing)
        :placeholder (if (= :multiple letter-spacing) (tr "settings.multiple") "--")
        :on-change #(handle-change % :letter-spacing)
        :is-nillable (= :multiple letter-spacing)
        :on-blur on-blur}]]

     ;; Feature 15 — paragraph spacing (px between paragraphs) and paragraph
     ;; indentation (first-line indent). Both reuse the existing line-height /
     ;; letter-spacing row layout classes (no new CSS). Additive: absent value
     ;; = no change to existing rendering. Only shown for text shapes
     ;; (advanced-spacing?), not the typography asset editor.
     (when advanced-spacing?
       [:div {:class (stl/css :line-height)
              :title (tr "workspace.options.text-options.paragraph-spacing")}
        [:span {:class (stl/css :icon)
                :alt (tr "workspace.options.text-options.paragraph-spacing")}
         deprecated-icon/text-lineheight]
        [:> deprecated-input/numeric-input*
         {:min 0
          :max 1000
          :step 1
          :default-value "0"
          :class (stl/css :line-height-input)
          :aria-label (tr "workspace.options.text-options.paragraph-spacing")
          :value (attr->string paragraph-spacing)
          :placeholder (if (= :multiple paragraph-spacing) (tr "settings.multiple") "--")
          :on-change #(handle-change % :paragraph-spacing)
          :is-nillable (= :multiple paragraph-spacing)
          :on-blur on-blur}]])

     (when advanced-spacing?
       [:div {:class (stl/css :letter-spacing)
              :title (tr "workspace.options.text-options.paragraph-indent")}
        [:span {:class (stl/css :icon)
                :alt (tr "workspace.options.text-options.paragraph-indent")}
         deprecated-icon/text-letterspacing]
        [:> deprecated-input/numeric-input*
         {:min 0
          :max 1000
          :step 1
          :default-value "0"
          :class (stl/css :letter-spacing-input)
          :aria-label (tr "workspace.options.text-options.paragraph-indent")
          :value (attr->string paragraph-indent)
          :placeholder (if (= :multiple paragraph-indent) (tr "settings.multiple") "--")
          :on-change #(handle-change % :paragraph-indent)
          :is-nillable (= :multiple paragraph-indent)
          :on-blur on-blur}]])]))

(mf/defc text-transform-options*
  [{:keys [values on-change on-blur]}]
  (let [text-transform (or (:text-transform values) "none")
        unset-value    (if (features/active-feature? @st/state "text-editor/v2") "none" "unset")
        handle-change
        (fn [type]
          (if (= text-transform type)
            (on-change {:text-transform unset-value})
            (on-change {:text-transform type}))
          (when (some? on-blur) (on-blur)))]

    [:div {:class (stl/css :text-transform)}
     [:& radio-buttons {:selected text-transform
                        :on-change handle-change
                        :name "text-transform"}
      [:& radio-button {:icon i/text-uppercase
                        :type "checkbox"
                        :title (tr "inspect.attributes.typography.text-transform.uppercase")
                        :value "uppercase"
                        :id "text-transform-uppercase"}]
      [:& radio-button {:icon i/text-mixed
                        :type "checkbox"
                        :value "capitalize"
                        :title (tr "inspect.attributes.typography.text-transform.capitalize")
                        :id "text-transform-capitalize"}]
      [:& radio-button {:icon i/text-lowercase
                        :type "checkbox"
                        :title (tr "inspect.attributes.typography.text-transform.lowercase")
                        :value "lowercase"
                        :id "text-transform-lowercase"}]]]))

(mf/defc text-options*
  [{:keys [ids editor values on-change on-blur show-recent advanced-spacing?]}]
  (let [full-size-selector? (and show-recent (= (mf/use-ctx ctx/sidebar) :right))
        opts (mf/props
              {:editor editor
               :ids ids
               :values values
               :on-change on-change
               :on-blur on-blur
               :show-recent show-recent
               :advanced-spacing? advanced-spacing?
               :full-size-selector full-size-selector?})]
    [:div {:class (stl/css-case :text-options true
                                :text-options-full-size full-size-selector?)}
     [:> font-options* opts]
     [:div {:class (stl/css :typography-variations)}
      [:> spacing-options* opts]
      [:> text-transform-options* opts]]]))

(mf/defc typography-advanced-options*
  {::mf/wrap [mf/memo]}
  [{:keys [is-visible typography is-editable name-input-ref on-close on-change on-name-blur
           is-local navigate-to-library on-key-down file-id is-asset?]}]
  (let [ref            (mf/use-ref nil)
        font-data      (fonts/get-font-data (:font-id typography))
        typography-id  (:id typography)
        show-actions?  (and is-asset? is-editable)

        on-delete
        (mf/use-fn
         (mf/deps typography-id file-id on-close)
         (fn []
           (on-close)
           (let [undo-id (js/Symbol)]
             (st/emit! (dwu/start-undo-transaction undo-id)
                       (dwl/delete-typography typography-id)
                       (dwl/sync-file file-id file-id :typographies typography-id)
                       (dwu/commit-undo-transaction undo-id)))))

        on-duplicate
        (mf/use-fn
         (mf/deps file-id typography-id)
         (fn []
           (st/emit! (dwl/duplicate-typography file-id typography-id))))]
    (fonts/ensure-loaded! (:font-id typography))

    (mf/use-effect
     (mf/deps is-visible)
     (fn []
       (when-let [node (mf/ref-val ref)]
         (when is-visible
           (dom/scroll-into-view-if-needed! node)))))

    (when is-visible
      [:div {:ref ref
             :class (stl/css :advanced-options-wrapper)}

       (if ^boolean is-editable
         [:*
          [:div {:class (stl/css :font-name-wrapper)}
           [:div {:class (stl/css :typography-sample-input)
                  :style {:font-family (:font-family typography)
                          :font-weight (:font-weight typography)
                          :font-style (:font-style typography)}}
            (tr "workspace.assets.typography.sample")]

           [:input
            {:class (stl/css :adv-typography-name)
             :type "text"
             :ref name-input-ref
             :default-value (:name typography)
             :max-length max-input-length
             :on-key-down on-key-down
             :on-blur on-name-blur}]

           [:div {:class (stl/css :action-btns)}
            (when show-actions?
              [:*
               [:> icon-button* {:variant "action"
                                 :aria-label (tr "workspace.assets.duplicate")
                                 :on-click on-duplicate
                                 :icon i/clipboard}]
               [:> icon-button* {:variant "action"
                                 :aria-label (tr "workspace.assets.delete")
                                 :on-click on-delete
                                 :icon i/delete}]])
            [:> icon-button* {:variant "action"
                              :aria-label (tr "labels.close")
                              :on-click on-close
                              :icon i/tick}]]]

          [:> text-options* {:values typography
                             :on-change on-change
                             :show-recent false}]]

         [:div {:class (stl/css :typography-info-wrapper)}
          [:div {:class (stl/css :typography-name-wrapper)}
           [:div {:class (stl/css :typography-sample)

                  :style {:font-family (:font-family typography)
                          :font-weight (:font-weight typography)
                          :font-style (:font-style typography)}}
            (tr "workspace.assets.typography.sample")]

           [:div {:class (stl/css :typography-name)
                  :title (:name typography)}
            (:name typography)]
           [:span {:class (stl/css :typography-font)}
            (:name font-data)]
           [:> icon-button* {:variant "ghost"
                             :aria-label (tr "labels.close")
                             :on-click on-close
                             :icon i/menu}]]

          [:div {:class (stl/css :info-row)}
           [:span {:class (stl/css :info-label)}  (tr "workspace.assets.typography.font-style")]
           [:span {:class (stl/css :info-content)} (:font-variant-id typography)]]

          [:div {:class (stl/css :info-row)}
           [:span {:class (stl/css :info-label)}  (tr "workspace.assets.typography.font-size")]
           [:span {:class (stl/css :info-content)} (:font-size typography)]]

          [:div {:class (stl/css :info-row)}
           [:span {:class (stl/css :info-label)}  (tr "workspace.assets.typography.line-height")]
           [:span {:class (stl/css :info-content)} (:line-height typography)]]

          [:div {:class (stl/css :info-row)}
           [:span {:class (stl/css :info-label)}  (tr "workspace.assets.typography.letter-spacing")]
           [:span {:class (stl/css :info-content)} (:letter-spacing typography)]]

          [:div {:class (stl/css :info-row)}
           [:span {:class (stl/css :info-label)}  (tr "workspace.assets.typography.text-transform")]
           [:span {:class (stl/css :info-content)} (:text-transform typography)]]

          (when-not is-local
            [:> button* {:variant "secondary"
                         :on-click navigate-to-library}
             (tr "workspace.assets.typography.go-to-edit")])])])))

(mf/defc typography-entry*
  [{:keys [file-id typography is-local is-selected on-click on-change on-detach on-context-menu is-editing is-renaming is-focus-name external-open* is-asset?]}]
  (let [name-input-ref       (mf/use-ref)
        read-only?           (mf/use-ctx ctx/workspace-read-only?)
        editable?            (and is-local (not read-only?))

        open*                (mf/use-state is-editing)
        open?                (deref open*)
        font-data            (fonts/get-font-data (:font-id typography))
        name-only?           (= (:name typography) (:name font-data))

        on-name-blur
        (mf/use-fn
         (mf/deps on-change)
         (fn [event]
           (let [name (dom/get-target-val event)]
             (when-not (str/blank? name)
               (on-change {:name name})
               (st/emit! #(update % :workspace-global dissoc :rename-typography))))))

        on-open
        (mf/use-fn #(reset! open* true))

        on-close
        (mf/use-fn #(reset! open* false))

        navigate-to-library
        (mf/use-fn
         (mf/deps file-id)
         (fn []
           (when file-id
             (st/emit! (dcm/go-to-workspace :file-id file-id)))))

        on-key-down
        (mf/use-fn
         (fn [event]
           (let [enter?     (kbd/enter? event)
                 esc?       (kbd/esc? event)
                 input-node (dom/get-target event)]
             (when ^boolean enter?
               (dom/blur! input-node))
             (when ^boolean esc?
               (dom/blur! input-node)))))]

    (mf/with-effect [is-editing]
      (when is-editing
        (reset! open* is-editing)))

    (mf/with-effect [open?]
      (when (some? external-open*)
        (reset! external-open* open?)))

    (mf/with-effect [is-focus-name]
      (when is-focus-name
        (tm/schedule
         #(when-let [node (mf/ref-val name-input-ref)]
            (dom/focus! node)
            (dom/select-text! node)))))

    [:*
     [:div {:class (stl/css-case :typography-entry true
                                 :selected ^boolean is-selected)
            :style {:display (when ^boolean open? "none")}}
      (if is-renaming
        [:div {:class (stl/css :font-name-wrapper)}
         [:div
          {:class (stl/css :typography-sample-input)
           :style {:font-family (:font-family typography)
                   :font-weight (:font-weight typography)
                   :font-style (:font-style typography)}}
          (tr "workspace.assets.typography.sample")]

         [:input
          {:class (stl/css :adv-typography-name)
           :type "text"
           :ref name-input-ref
           :default-value (:name typography)
           :max-length max-input-length
           :on-key-down on-key-down
           :on-blur on-name-blur}]]
        [:div
         {:class (stl/css-case :typography-selection-wrapper true
                               :is-selectable ^boolean on-click)
          :on-click on-click
          :on-context-menu on-context-menu}
         [:div
          {:class (stl/css :typography-sample)
           :style {:font-family (:font-family typography)
                   :font-weight (:font-weight typography)
                   :font-style (:font-style typography)}}
          (tr "workspace.assets.typography.sample")]

         [:div {:class (stl/css :name-block)
                :title (if name-only?
                         (:name typography)
                         (dm/str (:name typography) " (" (:name font-data) ")"))}
          (if name-only?
            [:span  {:class (stl/css :typography-name)} (:name typography)]
            [:*
             (:name typography)
             [:span  {:class (stl/css :typography-name :typography-font)} (:name font-data)]])]])
      [:div {:class (stl/css :element-set-actions)}
       (when ^boolean on-detach
         [:> icon-button* {:variant "action"
                           :aria-label (tr "settings.detach")
                           :on-click on-detach
                           :icon i/detach}])
       [:> icon-button* {:variant "action"
                         :aria-label (tr "labels.open")
                         :on-click on-open
                         :icon i/menu}]]]

     [:> typography-advanced-options*
      {:is-visible open?
       :on-close on-close
       :typography  typography
       :is-editable editable?
       :name-input-ref  name-input-ref
       :on-change  on-change
       :on-name-blur on-name-blur
       :on-key-down on-key-down
       :file-id file-id
       :is-asset? is-asset?
       :is-local  is-local
       :navigate-to-library navigate-to-library}]]))
