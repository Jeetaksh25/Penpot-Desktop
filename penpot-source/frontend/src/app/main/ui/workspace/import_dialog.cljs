;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucral en España SL

(ns app.main.ui.workspace.import-dialog
  "P1.22 — Import .sketch / .fig files modal.

  A single modal with two tabs (Sketch / Figma). Registered via
  `data.modal/components` under `:import-dialog` and shown with
  `(modal/show! :import-dialog {})`.

  Sketch tab: opens the native Tauri open dialog (`.sketch` filter), then
  invokes the Rust `import_sketch` command, converts the result via
  `file-import/convert-sketch->spec`, and commits with
  `file-import/apply-imported-spec` (which reuses `design-gen/apply-design-spec`).

  Figma tab: a file-key input + a token input (prefilled from localStorage
  `ovion.figma-token`). Invokes the Rust `import_figma` command, converts
  via `file-import/convert-figma->spec`, commits the same way. The token is
  saved to localStorage on import. Sentinel errors
  `figma-token-missing` / `figma-token-invalid` / `figma-file-key-missing`
  are matched exactly to render the right empty state.

  Coral accent `#f28b82`, Lucide inline SVG icons (stroke-width 2,
  currentColor), reduced-motion guard. Byte-identical-when-inactive: the
  modal only runs anything when the user clicks Import."
  (:require-macros
   [app.main.style :as stl])
  (:require
   ["@tauri-apps/plugin-dialog" :as tdialog]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.file-import :as file-import]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]
   [promesa.core :as p]
   [cuerdas.core :as str]))

;; ── Reduced-motion guard ─────────────────────────────────────────────────────

(defn- use-reduced-motion
  "Return true when the user prefers reduced motion. Memoized so it is
  stable across re-renders."
  []
  (mf/use-memo
   (mf/deps)
   (fn []
     (try
       (some-> js/window
               (.-matchMedia "(prefers-reduced-motion: reduce)")
               (.-matches))
       (catch :default _ false)))))

;; ── Inline Lucide icons (stroke-width 2, currentColor) ───────────────────────

(mf/defc upload-icon*
  "Lucide 'upload' icon, inline SVG (stroke-width 2, currentColor). Coral
  via currentColor inheritance from the button."
  [{:keys [size] :or {size 16}}]
  [:svg {:width size :height size :viewBox "0 0 24 24"
         :fill "none" :stroke "currentColor" :stroke-width 2
         :stroke-linecap "round" :stroke-linejoin "round"
         :aria-hidden true}
   [:path {:d "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"}]
   [:polyline {:points "17 8 12 3 7 8"}]
   [:line {:x1 12 :y1 3 :x2 12 :y2 15}]])

(mf/defc figma-icon*
  "A Figma-style icon, inline SVG (stroke-width 2, currentColor). Pragmatic
  — five-node abstract logo silhouette."
  [{:keys [size] :or {size 16}}]
  [:svg {:width size :height size :viewBox "0 0 24 24"
         :fill "none" :stroke "currentColor" :stroke-width 2
         :stroke-linecap "round" :stroke-linejoin "round"
         :aria-hidden true}
   [:path {:d "M8 2h4v6H8a3 3 0 0 1 0-6z"}]
   [:path {:d "M12 2h2a3 3 0 0 1 0 6h-2z"}]
   [:path {:d "M12 8h2a3 3 0 0 1 0 6h-2z"}]
   [:path {:d "M8 8h4v6H8a3 3 0 0 1 0-6z"}]
   [:path {:d "M8 14h4v4a3 3 0 1 1-4 0z"}]])

;; ── Modal ────────────────────────────────────────────────────────────────────

(mf/defc import-dialog
  {::mf/register modal/components
   ::mf/register-as :import-dialog}
  [{:keys []}]
  (let [tab*       (mf/use-state :sketch)
        tab        (mf/deref tab*)
        busy*      (mf/use-state false)
        busy       (mf/deref busy*)
        error*     (mf/use-state nil)
        error      (mf/deref error*)
        ;; Sketch state
        path*      (mf/use-state nil)
        path       (mf/deref path*)
        ;; Figma state
        file-key*  (mf/use-state "")
        file-key   (mf/deref file-key*)
        token*     (mf/use-state (file-import/load-figma-token))
        token      (mf/deref token*)
        reduced    (use-reduced-motion)

        close
        (mf/use-fn
         (fn []
           (st/emit! (modal/hide))))

        set-tab
        (mf/use-fn
         (fn [t]
           (when-not busy
             (mf/set-state! tab* t)
             (mf/set-state! error* nil))))

        on-pick-sketch
        (mf/use-fn
         (fn []
           (when-not busy
             (-> (tdialog/open #js {:multiple false
                                    :filters #js [#js {:name "Sketch file"
                                                       :extensions #js ["sketch"]}]})
                 (p/then (fn [picked]
                           (when (and (string? picked) (not (str/blank? picked)))
                             (mf/set-state! path* picked)
                             (mf/set-state! error* nil))))
                 (p/catch (fn [_] nil))))))

        commit-spec
        (mf/use-fn
         (mf/deps tab)
         (fn [spec]
           (if (or (nil? spec) (empty? (:frames spec)))
             (mf/set-state! error* (tr "workspace.import.empty"))
             (do
               (st/emit! (file-import/apply-imported-spec {:spec spec}))
               (st/emit! (modal/hide))))))

        on-import-sketch
        (mf/use-fn
         (mf/deps path)
         (fn []
           (if (str/blank? path)
             (mf/set-state! error* (tr "workspace.import.pick-file"))
             (do
               (mf/set-state! busy* true)
               (mf/set-state! error* nil)
               (-> (file-import/import-sketch path)
                   (p/then (fn [sketch-json]
                             (let [spec (file-import/convert-sketch->spec sketch-json)]
                               (mf/set-state! busy* false)
                               (commit-spec spec))))
                   (p/catch (fn [err]
                              (mf/set-state! busy* false)
                              (mf/set-state! error* (str (tr "workspace.import.failed") ": " err)))))))))

        on-import-figma
        (mf/use-fn
         (mf/deps file-key token)
         (fn []
           (let [fk (str/trim file-key)]
             (cond
               (str/blank? fk)
               (mf/set-state! error* (tr "workspace.import.figma-key-missing"))

               (str/blank? (str/trim token))
               (mf/set-state! error* (tr "workspace.import.figma-token-missing"))

               :else
               (do
                 ;; Persist the token for next time.
                 (file-import/save-figma-token (str/trim token))
                 (mf/set-state! busy* true)
                 (mf/set-state! error* nil)
                 (-> (file-import/import-figma fk (str/trim token))
                     (p/then (fn [figma-json]
                               (let [spec (file-import/convert-figma->spec figma-json)]
                                 (mf/set-state! busy* false)
                                 (commit-spec spec))))
                     (p/catch (fn [err]
                                (mf/set-state! busy* false)
                                (let [s (str err)]
                                  (cond
                                    (= s "figma-token-missing")
                                    (mf/set-state! error* (tr "workspace.import.figma-token-missing"))
                                    (= s "figma-token-invalid")
                                    (mf/set-state! error* (tr "workspace.import.figma-token-invalid"))
                                    (= s "figma-file-key-missing")
                                    (mf/set-state! error* (tr "workspace.import.figma-key-missing"))
                                    :else
                                    (mf/set-state! error* (str (tr "workspace.import.failed") ": " s))))))))))))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-header)}
       [:h2 {:class (stl/css :modal-title)}
        (tr "workspace.import.title")]
       [:div {:class (stl/css :modal-close-btn)}
        [:> icon-button* {:variant "ghost"
                          :aria-label (tr "labels.close")
                          :on-click close
                          :icon i/close}]]]

      ;; Tabs
      [:div {:class (stl/css :modal-content)}
       [:div {:style #js {:display "flex" :gap "8px" :marginBottom "16px"}}
        [:button {:type "button"
                  :on-click #(set-tab :sketch)
                  :style #js {:flex "1"
                              :padding "10px 12px"
                              :border "1px solid"
                              :borderColor (if (= tab :sketch) "#f28b82" "#e5e7eb")
                              :borderRadius "8px"
                              :background (if (= tab :sketch) "rgba(242,139,130,0.10)" "transparent")
                              :color (if (= tab :sketch) "#f28b82" "#374151")
                              :cursor (if busy "not-allowed" "pointer")
                              :font-weight "500"
                              :display "flex" :align-items "center" :gap "8px"
                              :transition (if reduced "none" "background 120ms ease, border-color 120ms ease")}}
         [:> upload-icon* {:size 16}]
         (tr "workspace.import.tab-sketch")]
        [:button {:type "button"
                  :on-click #(set-tab :figma)
                  :style #js {:flex "1"
                              :padding "10px 12px"
                              :border "1px solid"
                              :borderColor (if (= tab :figma) "#f28b82" "#e5e7eb")
                              :borderRadius "8px"
                              :background (if (= tab :figma) "rgba(242,139,130,0.10)" "transparent")
                              :color (if (= tab :figma) "#f28b82" "#374151")
                              :cursor (if busy "not-allowed" "pointer")
                              :font-weight "500"
                              :display "flex" :align-items "center" :gap "8px"
                              :transition (if reduced "none" "background 120ms ease, border-color 120ms ease")}}
         [:> figma-icon* {:size 16}]
         (tr "workspace.import.tab-figma")]]

       (when error
         [:div {:style #js {:marginBottom "12px"
                            :padding "10px 12px"
                            :borderRadius "8px"
                            :background "rgba(220,38,38,0.08)"
                            :color "#b91c1c"
                            :fontSize "13px"}}
          error])

       (if (= tab :sketch)
         ;; Sketch tab
         [:div {:style #js {:display "flex" :flexDirection "column" :gap "12px"}}
          [:p {:style #js {:color "#6b7280" :fontSize "13px" :margin "0"}}
           (tr "workspace.import.sketch-hint")]
          [:button {:type "button"
                    :on-click on-pick-sketch
                    :disabled busy
                    :style #js {:padding "10px 12px"
                                :border "1px dashed #cbd5e1"
                                :borderRadius "8px"
                                :background "#f9fafb"
                                :cursor (if busy "not-allowed" "pointer")
                                :color "#374151"
                                :display "flex" :align-items "center" :gap "8px"
                                :transition (if reduced "none" "background 120ms ease")}}
           [:> upload-icon* {:size 16}]
           (if (str/blank? path)
             (tr "workspace.import.pick-sketch")
             (let [nm (-> path (str/split #"[\\/]") last)]
               (str (tr "workspace.import.selected") ": " (or nm path))))]
          (when busy
            [:p {:style #js {:color "#6b7280" :fontSize "13px" :margin "0"}}
             (tr "workspace.import.loading")])]

         ;; Figma tab
         [:div {:style #js {:display "flex" :flexDirection "column" :gap "12px"}}
          [:p {:style #js {:color "#6b7280" :fontSize "13px" :margin "0"}}
           (tr "workspace.import.figma-hint")]
          [:label {:style #js {:fontSize "12px" :color "#374151" :fontWeight "500"}}
           (tr "workspace.import.figma-file-key")]
          [:input {:type "text"
                   :value file-key
                   :disabled busy
                   :on-change #(mf/set-state! file-key* (dom/get-target-val %))
                   :placeholder "https://www.figma.com/file/<key>/…"
                   :style #js {:padding "8px 10px"
                               :border "1px solid #e5e7eb"
                               :borderRadius "8px"
                               :fontSize "13px"
                               :width "100%"}}]
          [:label {:style #js {:fontSize "12px" :color "#374151" :fontWeight "500"}}
           (tr "workspace.import.figma-token-label")]
          [:input {:type "password"
                   :value token
                   :disabled busy
                   :on-change #(mf/set-state! token* (dom/get-target-val %))
                   :placeholder (tr "workspace.import.figma-token-placeholder")
                   :style #js {:padding "8px 10px"
                               :border "1px solid #e5e7eb"
                               :borderRadius "8px"
                               :fontSize "13px"
                               :width "100%"}}]
          (when busy
            [:p {:style #js {:color "#6b7280" :fontSize "13px" :margin "0"}}
             (tr "workspace.import.loading")])])]

      ;; Footer
      [:div {:class (stl/css :modal-footer)}
       [:div {:class (stl/css :action-buttons)}
        [:> button* {:variant "secondary" :on-click close :disabled busy}
         (tr "ds.confirm-cancel")]
        [:> button* {:variant "primary"
                     :on-click (if (= tab :sketch) on-import-sketch on-import-figma)
                     :disabled (or busy (and (= tab :sketch) (str/blank? path))
                                  (and (= tab :figma) (str/blank? (str/trim file-key))))
                     :style #js {:background "#f28b82"
                                 :borderColor "#f28b82"}}
         (tr "workspace.import.import")]]]]]))