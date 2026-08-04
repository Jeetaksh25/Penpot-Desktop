;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.inspect.exports
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.data.exports.assets :as de]
   [app.main.data.viewer :as dv]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr c]]
   [app.util.keyboard :as kbd]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

;; Figma-parity reusable export presets (gap #76). Saved presets live in
;; the file data model (:export-presets, see file.cljc schema:data). This
;; panel reads them from the current file data and offers an "Apply preset"
;; dropdown that replaces the local export rows with the preset's
;; format / scale / suffix. The dropdown renders only when at least one
;; preset exists, so files without presets are byte-identical. Saving /
;; deleting presets requires a workspace file-data mutation event (rpc)
;; owned by the workspace data layer, so the save / delete affordances are
;; deferred here (the schema round-trips and the apply UI is wired).
(def ^:private export-presets-ref
  (l/derived #(some-> % :export-presets) refs/workspace-data))

(def ^:private exports-cache-ref
  (l/derived :inspect-exports-cache st/state))

(mf/defc exports
  {::mf/wrap [#(mf/memo % =)]}
  [{:keys [shapes page-id file-id share-id type] :as props}]
  (let [exports     (mf/use-state [])
        xstate      (mf/deref refs/export)
        vstate      (mf/deref refs/viewer-data)
        page        (get-in vstate [:pages page-id])
        filename    (if (= (count shapes) 1)
                      (let [sname   (-> shapes first :name)
                            suffix (-> @exports first :suffix)]
                        (cond-> sname
                          (and (= 1 (count @exports)) (some? suffix))
                          (str suffix)))
                      (:name page))

        scale-enabled?
        (mf/use-callback
         (fn [export]
           (#{:png :jpeg :webp} (:type export))))

        in-progress? (:in-progress xstate)

        on-download
        (fn [event]
          (dom/prevent-default event)
          (if (= :multiple type)
            (st/emit! (de/show-viewer-export-dialog {:shapes shapes
                                                     :exports @exports
                                                     :name filename
                                                     :page-id page-id
                                                     :file-id file-id
                                                     :share-id share-id}))

            ;; In other all cases we only allowed to have a single
            ;; shape-id because multiple shape-ids are handled
            ;; separately by the export-modal.
            (let [defaults (-> {:page-id page-id
                                :file-id file-id
                                :name filename
                                :object-id (-> shapes first :id)}
                               (cond-> share-id (assoc :share-id share-id)))
                  exports  (mapv #(merge % defaults) @exports)]
              (st/emit!
               (de/request-export {:exports exports})
               (de/export-shapes-event exports "viewer")))))

        shapes-key
        (mf/use-memo (mf/deps shapes) #(vec (sort (map :id shapes))))

        add-export
        (mf/use-callback
         (mf/deps shapes exports)
         (fn []
           (let [xspec {:type :png
                        :suffix ""
                        :scale 1}
                 new-exports (conj @exports xspec)]
             (reset! exports new-exports)
             (st/emit! (dv/update-exports-cache shapes-key new-exports)))))

        delete-export
        (mf/use-callback
         (mf/deps shapes exports)
         (fn [index]
           (let [new-exports (let [[before after] (split-at index @exports)]
                               (d/concat-vec before (rest after)))]
             (reset! exports new-exports)
             (st/emit! (dv/update-exports-cache shapes-key new-exports)))))

        on-scale-change
        (mf/use-callback
         (mf/deps shapes exports)
         (fn [index event]
           (let [scale (d/parse-double event)
                 new-exports (assoc-in @exports [index :scale] scale)]
             (reset! exports new-exports)
             (st/emit! (dv/update-exports-cache shapes-key new-exports)))))

        on-suffix-change
        (mf/use-callback
         (mf/deps shapes exports)
         (fn [event]
           (let [value (dom/get-target-val event)
                 index (-> (dom/get-current-target event)
                           (dom/get-data "value")
                           (d/parse-integer))
                 new-exports (assoc-in @exports [index :suffix] value)]
             (reset! exports new-exports)
             (st/emit! (dv/update-exports-cache shapes-key new-exports)))))

        on-type-change
        (mf/use-callback
         (mf/deps shapes exports)
         (fn [index event]
           (let [type (keyword event)
                 new-exports (assoc-in @exports [index :type] type)]
             (reset! exports new-exports)
             (st/emit! (dv/update-exports-cache shapes-key new-exports)))))

        manage-key-down
        (mf/use-callback
         (fn [event]
           (let [esc?   (kbd/esc? event)]
             (when esc?
               (dom/blur! (dom/get-target event))))))

        ;; Figma-parity reusable export presets (gap #76). Reads the saved
        ;; presets from the current file data and, on selection, replaces the
        ;; local export rows with the preset's format / scale / suffix.
        presets (mf/deref export-presets-ref)
        preset-options (mf/with-memo [presets]
                         (mapv (fn [p]
                                 {:value (str (:id p))
                                  :label (:name p)})
                               (or presets [])))
        on-apply-preset
        (mf/use-callback
         (mf/deps shapes-key presets)
         (fn [preset-id-str]
           (when (seq presets)
             (let [preset (first (filter #(= (str (:id %)) preset-id-str) presets))]
               (when preset
                 (let [row {:type (or (:type preset) :png)
                            :suffix (or (:suffix preset) "")
                            :scale (or (:scale preset) 1)}]
                   (reset! exports [row])
                   (st/emit! (dv/update-exports-cache shapes-key [row]))))))))

        size-options [{:value "0.5" :label "0.5x"}
                      {:value "0.75" :label "0.75x"}
                      {:value "1" :label "1x"}
                      {:value "1.5" :label "1.5x"}
                      {:value "2" :label "2x"}
                      {:value "4" :label "4x"}
                      {:value "6" :label "6x"}]

        format-options [{:value "png" :label "PNG"}
                        {:value "jpeg" :label "JPG"}
                        {:value "webp" :label "WEBP"}
                        {:value "svg" :label "SVG"}
                        {:value "pdf" :label "PDF"}
                        {:value "react" :label "React"}
                        {:value "nextjs" :label "Next.js"}
                        {:value "react-native" :label "React Native"}
                        {:value "android-xml" :label "Android XML"}
                        {:value "winui3-xml" :label "WinUI 3 XAML"}
                        {:value "flutter" :label "Flutter"}
                        {:value "tailwind" :label "Tailwind CSS"}
                        {:value "compose" :label "Jetpack Compose"}]]

    (mf/use-effect
     (mf/deps shapes)
     (fn []
       (let [shapes-key (vec (sort (map :id shapes)))
             cached     (get @exports-cache-ref shapes-key)]
         (if (some? cached)
           (reset! exports cached)
           (reset! exports (->> shapes
                                (mapcat #(:exports % []))
                                (distinct)
                                vec))))))
    [:div {:class (stl/css :element-set)}
     [:div {:class (stl/css :element-title)}
      [:> title-bar* {:collapsable false
                      :title       (tr "workspace.options.export")
                      :class       (stl/css :title-spacing-export-viewer)}
       [:button {:class (stl/css :add-export)
                 :on-click add-export} deprecated-icon/add]]]

     ;; Figma-parity reusable export presets (gap #76). Apply-preset dropdown;
     ;; renders only when the file has at least one saved preset, so files
     ;; without presets are byte-identical.
     (when (seq presets)
       [:div {:class (stl/css :export-presets)
              :data-testid "export.presets"}
        [:span {:class (stl/css :presets-label)}
         (tr "workspace.options.export.presets")]
        [:& select {:options preset-options
                    :default-value ""
                    :dropdown-class (stl/css :dropdown-upwards)
                    :on-change on-apply-preset}]])

     (cond
       (= :multiple exports)
       [:div {:class (stl/css :multiple-exports)}
        [:div {:class (stl/css :label)} (tr "settings.multiple")]
        [:div {:class (stl/css :actions)}
         [:button {:class (stl/css :action-btn)
                   :on-click ()}
          deprecated-icon/remove-icon]]]

       (seq @exports)
       [:div {:class (stl/css :element-set-content)}
        (for [[index export] (d/enumerate @exports)]
          [:div {:class (stl/css :element-group)
                 :key index}
           [:div {:class (stl/css :input-wrapper)}
            [:div  {:class (stl/css :format-select)}
             [:& select
              {:default-value (d/name (:type export))
               :options format-options
               :dropdown-class (stl/css :dropdown-upwards)
               :on-change (partial on-type-change index)}]]
            (when (scale-enabled? export)
              [:div {:class (stl/css :size-select)}
               [:& select
                {:default-value (str (:scale export))
                 :options size-options
                 :dropdown-class (stl/css :dropdown-upwards)
                 :on-change (partial on-scale-change index)}]])
            [:label {:class (stl/css :suffix-input)
                     :for "suffix-export-input"}
             [:input {:class (stl/css :type-input)
                      :id "suffix-export-input"
                      :type "text"
                      :value (:suffix export)
                      :placeholder (tr "workspace.options.export.suffix")
                      :data-value (str index)
                      :on-change on-suffix-change
                      :on-key-down manage-key-down}]]]

           [:button {:class (stl/css :action-btn)
                     :on-click (partial delete-export index)}
            deprecated-icon/remove-icon]])])
     (when (or (= :multiple exports) (seq @exports))
       [:button
        {:on-click (when-not in-progress? on-download)
         :class (stl/css-case
                 :export-btn true
                 :btn-disabled in-progress?)
         :disabled in-progress?}
        (if in-progress?
          (tr "workspace.options.exporting-object")
          (tr "workspace.options.export-object" (c (count shapes))))])]))

