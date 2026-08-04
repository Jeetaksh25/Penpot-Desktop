;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.inspect.code
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes :as gsh]
   [app.common.types.shape-tree :as ctst]
   [app.config :as cfg]
   [app.main.data.event :as ev]
   [app.main.data.exports.code :as code]
   [app.main.fonts :as fonts]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.code-block :refer [code-block*]]
   [app.main.ui.components.copy-button :refer [copy-button*]]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.inspect.a11y :refer [a11y-authoring* a11y-contrast*]]
   [app.main.ui.hooks.resize :refer [use-resize-hook]]
   [app.main.ui.icons :as deprecated-icon]
   [app.main.ui.shapes.text.fontfaces :refer [shapes->fonts]]
   [app.util.clipboard :as clipboard]
   [app.util.code-beautify :as cb]
   [app.util.code-gen :as cg]
   [app.util.dom :as dom]
   [app.util.http :as http]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [clojure.string :as cstr]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def embed-images? true)
(def remove-localhost? true)

;; Markup / code-export format options shown in the Inspect "Code" panel.
;; The first two (html, svg) are the classic markup targets; the rest are
;; UI-framework code exporters that produce a single self-contained source
;; file (no separate CSS section, no "Copy all code").
(def markup-options
  [{:value "html"         :label "HTML"}
   {:value "svg"           :label "SVG"}
   {:value "react"         :label "React"}
   {:value "nextjs"        :label "Next.js"}
   {:value "react-native"  :label "React Native"}
   {:value "android-xml"   :label "Android XML"}
   {:value "winui3-xml"    :label "WinUI 3 XAML"}
   {:value "flutter"       :label "Flutter"}
   {:value "tailwind"      :label "Tailwind CSS"}
   {:value "swift"         :label "SwiftUI"}])

(def page-template
  "<!DOCTYPE html>
<html>
  <head>
    <style>
    %s
    </style>
  </head>
  <body>
  %s
  </body>
</html>")

;; FIXME: this code need to be refactored
(defn get-viewer-objects
  ([]
   (let [route      (deref refs/route)
         page-id    (:page-id (:query-params route))]
     (get-viewer-objects page-id)))
  ([page-id]
   (l/derived
    (fn [state]
      (let [objects (refs/get-viewer-objects state page-id)]
        objects))
    st/state =)))

(defn- use-objects [from]
  (let [page-objects-ref
        (mf/with-memo [from]
          (if (= from :workspace)
            ;; FIXME: fix naming consistency issues
            refs/workspace-page-objects
            (get-viewer-objects)))]
    (mf/deref page-objects-ref)))

(defn- shapes->images
  [shapes]
  (->> shapes
       (keep
        (fn [shape]
          (when-let [data (or (:metadata shape) (:fill-image shape) (-> shape :fills first :fill-image))]
            [(:id shape) (cfg/resolve-file-media data)])))))

(defn- replace-map
  [value map]
  (reduce
   (fn [value [old new]]
     (str/replace value old new))
   value map))

(defn gen-all-code
  [style-code markup-code images-data fonts-data]
  (let [markup-code (cond-> markup-code
                      embed-images? (replace-map images-data))

        style-code (cond-> style-code
                     embed-images? (replace-map (merge images-data fonts-data)))]
    (str/format page-template style-code markup-code)))

;; Figma-parity px/rem toggle (gap #69). Converts `Npx` tokens in a CSS
;; string to `(N/16)rem`. Pure post-processing — only applied when the
;; user selects "rem"; the default "px" path is byte-identical to before.
(defn- px->rem
  [css]
  (cstr/replace
   css
   #"[0-9]+(?:\.[0-9]+)?px"
   (fn [match]
     (let [n (js/parseFloat match)
           rem (/ n 16)
           s  (-> (str rem)
                  (str/replace #"\.(\d*?[1-9])0+$" ".$1")
                  (str/replace #"\.$" ""))]
       (str s "rem")))))

(mf/defc code*
  [{:keys [shapes frame on-expand from]}]
  (let [style-type*    (mf/use-state "css")
        markup-type*   (mf/use-state "html")
        fontfaces-css* (mf/use-state nil)
        images-data*   (mf/use-state nil)
        fonts-data*    (mf/use-state nil)
        ;; Figma-parity px/rem toggle (gap #69). When "rem", the CSS style
        ;; section's px values are scaled by 1/16 to rem. Default "px" =
        ;; byte-identical to the legacy output. Applies only to the CSS
        ;; section (frameworks use their own unit systems).
        unit*          (mf/use-state "px")

        style-type     (deref style-type*)
        markup-type    (deref markup-type*)
        fontfaces-css  (deref fontfaces-css*)
        images-data    (deref images-data*)
        fonts-data     (deref fonts-data*)
        unit           (deref unit*)

        collapsed*        (mf/use-state #{})
        collapsed-css?    (contains? @collapsed* :css)
        collapsed-markup? (contains? @collapsed* :markup)

        objects        (use-objects from)

        shapes
        (mf/with-memo [shapes frame]
          (mapv #(gsh/translate-to-frame % frame) shapes))

        all-children
        (mf/use-memo
         (mf/deps shapes objects)
         (fn []
           (->> shapes
                (map :id)
                (cfh/selected-with-children objects)
                (ctst/sort-z-index objects)
                (map (d/getf objects)))))

        fonts
        (mf/with-memo [all-children]
          (shapes->fonts all-children))

        images-urls
        (mf/with-memo [all-children]
          (shapes->images all-children))

        style-code
        (mf/use-memo
         (mf/deps fontfaces-css style-type shapes all-children cg/generate-style-code)
         (fn []
           (dm/str
            fontfaces-css "\n"
            (-> (cg/generate-style-code objects style-type shapes all-children)
                (cb/format-code style-type)))))

        ;; Figma-parity px/rem toggle (gap #69). The CSS shown/copied is the
        ;; generated `style-code` with px values scaled to rem when the user
        ;; selects "rem"; "px" (default) returns `style-code` unchanged.
        display-style-code
        (mf/use-memo
         (mf/deps style-code unit)
         (fn []
           (if (= unit "rem") (px->rem style-code) style-code)))

        framework?
        (cg/framework? markup-type)

        framework-code
        (mf/use-memo
         (mf/deps markup-type shapes)
         (fn []
           (when framework?
             (cg/generate-framework-code objects markup-type shapes))))

        ;; Figma-parity Code Connect (gap #40). When the selected shape is a
        ;; component instance whose main component authored a Code Connect
        ;; template for the currently selected framework, surface it here for
        ;; display. Replacing the auto-generated `framework-code` body with the
        ;; authored template inside every framework emitter is DEFERRED
        ;; (high blast-radius across all framework namespaces); this is a
        ;; read-only surface in the Inspect panel.
        code-connect-template
        (mf/use-memo
         (mf/deps markup-type shapes objects)
         (fn []
           (when (and framework? (= 1 (count shapes)))
             (cg/component-code-connect-template objects markup-type (first shapes)))))

        markup-code
        (mf/use-memo
         (mf/deps markup-type shapes images-data framework-code)
         (fn []
           (if framework?
             framework-code
             (cg/generate-formatted-markup-code objects markup-type shapes))))

        on-markup-copied
        (mf/use-fn
         (mf/deps markup-type from)
         (fn []
           (let [origin (if (= :workspace from)
                          "workspace"
                          "viewer")]
             (st/emit! (ev/event
                        {::ev/name "copy-inspect-code"
                         ::ev/origin origin
                         :type markup-type})))))

        on-style-copied
        (mf/use-fn
         (mf/deps style-type from)
         (fn []
           (let [origin (if (= :workspace from)
                          "workspace"
                          "viewer")]
             (st/emit! (ev/event
                        {::ev/name "copy-inspect-style"
                         ::ev/origin origin
                         :type style-type})))))

        {on-markup-pointer-down :on-pointer-down
         on-markup-lost-pointer-capture :on-lost-pointer-capture
         on-markup-pointer-move :on-pointer-move
         markup-size :size}
        (use-resize-hook :code 400 100 800 :y false :bottom)

        {on-style-pointer-down :on-pointer-down
         on-style-lost-pointer-capture :on-lost-pointer-capture
         on-style-pointer-move :on-pointer-move
         style-size :size}
        (use-resize-hook :code 400 100 800 :y false :bottom)

        ;; set-style
        ;; (mf/use-fn
        ;;  (fn [value]
        ;;    (reset! style-type* value)))

        set-markup
        (mf/use-fn
         (mf/deps markup-type*)
         (fn [value]
           (reset! markup-type* value)))

        ;; Figma-parity px/rem toggle (gap #69).
        set-unit
        (mf/use-fn
         (mf/deps unit*)
         (fn [value]
           (reset! unit* value)))

        handle-copy-all-code
        (mf/use-fn
         (mf/deps display-style-code markup-code images-data fonts-data)
         (fn []
           (clipboard/to-clipboard (gen-all-code display-style-code markup-code images-data fonts-data))
           (let [origin (if (= :workspace from)
                          "workspace"
                          "viewer")]
             (st/emit! (ev/event
                        {::ev/name "copy-inspect-code"
                         ::ev/origin origin
                         :type "all"})))))

        handle-download-code
        (mf/use-fn
         (mf/deps markup-type framework-code shapes fontfaces-css fonts-data objects)
         (fn []
           (when (and framework? (some? framework-code))
             (let [base-name (:name (first shapes))
                   origin (if (= :workspace from) "workspace" "viewer")]
               ;; Feature 2: download a full multi-file project ZIP (component +
               ;; scaffold + bundled @font-face fonts) via the Tauri Save-As
               ;; dialog + `write_code_zip`, with an in-browser blob fallback.
               ;; `fontfaces-css` / `fonts-data` are already resolved above.
               (st/emit! (ev/event
                          {::ev/name "download-inspect-code"
                           ::ev/origin origin
                           :type markup-type})
                         (code/request-code-project-export
                          {:objects objects
                           :type markup-type
                           :shapes shapes
                           :fontfaces-css fontfaces-css
                           :fonts-data fonts-data
                           :base-name base-name}))))))

        ;;handle-open-review
        ;;(mf/use-fn
        ;; (fn []
        ;;   (st/emit! (dp/open-preview-selected))))

        handle-collapse
        (mf/use-fn
         (fn [event]
           (let [panel-type (-> (dom/get-current-target event)
                                (dom/get-data "type")
                                (keyword))]
             (swap! collapsed*
                    (fn [collapsed]
                      (if (contains? collapsed panel-type)
                        (disj collapsed panel-type)
                        (conj collapsed panel-type)))))))
        copy-css-fn
        (mf/use-fn
         (mf/deps display-style-code images-data fonts-data)
         #(replace-map display-style-code (merge images-data fonts-data)))

        copy-html-fn
        (mf/use-fn
         (mf/deps markup-code images-data)
         #(replace-map markup-code images-data))]

    (mf/with-effect [fonts]
      (let [sub (->> (rx/from fonts)
                     (rx/merge-map fonts/fetch-font-css)
                     (rx/reduce conj [])
                     (rx/subs!
                      (fn [result]
                        (let [css (str/join "\n" result)]
                          (reset! fontfaces-css* css)))))]
        #(rx/dispose! sub)))

    ;; Resolve the font URLs to data URIs. The inspect view keeps the original
    ;; URLs (more readable), but copying embeds the fonts so the styles render
    ;; outside of Penpot, where the original URLs require auth/CORS.
    (mf/with-effect [fontfaces-css]
      (let [sub (->> (rx/from (fonts/extract-fontface-urls (or fontfaces-css "")))
                     (rx/merge-map
                      (fn [uri]
                        (->> (http/fetch-data-uri uri true)
                             (rx/catch (fn [_] (rx/of (hash-map uri uri)))))))
                     (rx/reduce conj {})
                     (rx/subs!
                      (fn [result]
                        (reset! fonts-data* result))))]
        #(rx/dispose! sub)))

    (mf/with-effect [images-urls]
      (let [sub (->> (rx/from images-urls)
                     (rx/merge-map
                      (fn [[_ uri]]
                        (->> (http/fetch-data-uri uri true)
                             (rx/catch (fn [_] (rx/of (hash-map uri uri)))))))
                     (rx/reduce conj {})
                     (rx/subs!
                      (fn [result]
                        (reset! images-data* result))))]
        #(rx/dispose! sub)))

    [:div {:class (stl/css-case :element-options true
                                :viewer-code-block (= :viewer from))}
     ;; Figma-parity accessibility insights (gap #77). WCAG contrast
     ;; checker for a single text shape. The component renders nothing
     ;; for any other selection, so this is purely additive + guarded.
     [:> a11y-contrast* {:objects objects :shapes shapes}]
     ;; ALL_APPS_PARITY P1.06: ARIA authoring (accessible name + role)
     ;; for any single selected shape. Renders nothing for multi-shape
     ;; selections, so purely additive + guarded.
     [:> a11y-authoring* {:shapes shapes}]
     [:div {:class (stl/css :attributes-block)}
      (if framework?
        [:button {:class (stl/css :download-button)
                  :on-click handle-download-code}
         "Download"]
        [:button {:class (stl/css :download-button)
                  :on-click handle-copy-all-code}
         "Copy all code"])]

     (when (some? code-connect-template)
       [:div {:class (stl/css :code-connect-banner)}
        [:div {:class (stl/css :code-connect-label)}
         (tr "inspect.code.code-connect")]
        [:> code-block* {:type markup-type
                         :code code-connect-template}]])

     #_[:div.attributes-block
        [:button.download-button {:on-click handle-open-review}
         "Preview"]]

     (when-not framework?
       [:div {:class (stl/css-case :code-block true
                                   :collapsed collapsed-css?)}
        [:div {:class (stl/css :code-row-lang)}
         [:button {:class (stl/css :toggle-btn)
                   :data-type "css"
                   :on-click handle-collapse}
          [:span {:class (stl/css-case
                          :collapsabled-icon true
                          :rotated collapsed-css?)}
           deprecated-icon/arrow]]

         [:div {:class (stl/css :code-lang-option)}
          "CSS"]
         ;; Figma-parity px/rem toggle (gap #69). Scales the CSS section's
         ;; px values by 1/16 to rem when "rem" is selected; "px" (default)
         ;; is byte-identical to the legacy output.
         [:& select {:default-value unit
                     :options [{:value "px"  :label (tr "inspect.code.unit.px")}
                               {:value "rem" :label (tr "inspect.code.unit.rem")}]
                     :on-change set-unit
                     :class (stl/css :code-unit-select)}]

         [:div {:class (stl/css :action-btns)}
          [:button {:class (stl/css :expand-button)
                    :on-click on-expand}
           deprecated-icon/code]

          [:> copy-button* {:data copy-css-fn
                            :class (stl/css :css-copy-btn)
                            :on-copied on-style-copied}]]]

        (when-not collapsed-css?
          [:div {:class (stl/css :code-row-display)
                 :style {:--code-height (dm/str (or style-size 400) "px")}}
           [:> code-block* {:type style-type
                            :code display-style-code}]])

        [:div {:class (stl/css :resize-area)
               :on-pointer-down on-style-pointer-down
               :on-lost-pointer-capture on-style-lost-pointer-capture
               :on-pointer-move on-style-pointer-move}]])

     [:div {:class (stl/css-case :code-block true
                                 :collapsed collapsed-markup?)}
      [:div {:class (stl/css :code-row-lang)}
       [:button {:class (stl/css :toggle-btn)
                 :data-type "markup"
                 :on-click handle-collapse}
        [:span {:class (stl/css-case
                        :collapsabled-icon true
                        :rotated collapsed-markup?)}
         deprecated-icon/arrow]]

       [:& select {:default-value markup-type
                   :options markup-options
                   :on-change set-markup
                   :dropdown-class (stl/css :code-lang-select)
                   :class (stl/css :code-lang-options)}]

       [:div {:class (stl/css :action-btns)}
        (when-not framework?
          [:button {:class (stl/css :expand-button)
                    :on-click on-expand}
           deprecated-icon/code])

        [:> copy-button* {:data copy-html-fn
                          :class (stl/css :html-copy-btn)
                          :on-copied on-markup-copied}]]]

      (when-not collapsed-markup?
        [:div {:class (stl/css :code-row-display)
               :style {:--code-height (dm/str (or markup-size 400) "px")}}
         [:> code-block* {:type markup-type
                          :code markup-code}]])

      [:div {:class (stl/css :resize-area)
             :on-pointer-down on-markup-pointer-down
             :on-lost-pointer-capture on-markup-lost-pointer-capture
             :on-pointer-move on-markup-pointer-move}]]]))
