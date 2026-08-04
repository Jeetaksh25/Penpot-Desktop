;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.common :as dcm]
   [app.main.data.helpers :as dsh]
   [app.main.data.persistence :as dps]
   [app.main.data.plugins :as dpl]
   [app.main.data.workspace :as dw]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.router :as-alias rt]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.product.loader :refer [loader*]]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.hooks.resize :refer [use-resize-observer]]
   [app.main.ui.modal :refer [modal-container*]]
   [app.main.ui.workspace.colorpicker]
   [app.main.ui.workspace.ai-bar :refer [ai-bar*]]
   [app.main.ui.workspace.context-menu :refer [context-menu*]]
   [app.main.ui.workspace.coordinates :as coordinates]
   [app.main.ui.workspace.libraries]
   [app.main.ui.workspace.nudge]
   [app.main.ui.workspace.publish]
   [app.main.ui.workspace.repeat-grid]
   [app.main.ui.workspace.rotate-copies]
   [app.main.ui.workspace.palette :refer [palette*]]
   [app.main.ui.workspace.plugins]
   [app.main.ui.workspace.sidebar :refer [sidebar*]]
   [app.main.ui.workspace.sidebar.history :refer [history-toolbox*]]
   [app.main.ui.workspace.tokens.export]
   [app.main.ui.workspace.tokens.export.modal]
   [app.main.ui.workspace.tokens.import]
   [app.main.ui.workspace.tokens.import.modal]
   [app.main.ui.workspace.tokens.management.forms.modals]
   [app.main.ui.workspace.tokens.management.forms.rename-node-modal]
   [app.main.ui.workspace.tokens.remapping-modal]
   [app.main.ui.workspace.tokens.settings]
   [app.main.ui.workspace.tokens.themes.create-modal]
   [app.main.data.workspace.mcp-server]
   [app.main.ui.workspace.viewport :refer [viewport*]]
   [app.main.ui.workspace.webgl-unavailable-modal]
   [app.main.ui.workspace.workshop :refer [workshop-panel*]]
   [app.main.data.workspace.workshop :as wsp]
   [app.main.data.workspace.team-sharing :as ts]
   [app.main.ui.workspace.comments-overlay :refer [comments-overlay*]]
   [app.main.data.workspace.on-page-edit :as dope]
   [app.main.ui.workspace.on-page-preview :refer [on-page-preview*]]
   [app.util.debug :as dbg]
   [app.util.dom :as dom]
   [app.util.globals :as globals]
   [app.util.i18n :as i18n :refer [tr]]
   [goog.events :as events]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(mf/defc workspace-content*
  {::mf/private true}
  [{:keys [file layout page wglobal]}]

  (let [palete-size (mf/use-state nil)
        selected    (mf/deref refs/selected-shapes)
        page-id     (get page :id)

        vport       (mf/deref refs/workspace-vport)
        {:keys [options-mode]} wglobal

        ;; Focus mode (P2.02): when active the canvas is isolated on the
        ;; selection and the workspace chrome is collapsed to a
        ;; distraction-free layout. Off by default — byte-identical to the
        ;; prior render when false.
        focus-mode? (mf/deref refs/focus-mode?)

        ;; Comments mode (P2.37): when ON, a coral comment-pin overlay is
        ;; rendered on the canvas. Off by default — byte-identical to the
        ;; prior render when false (the overlay emits nothing).
        comments-mode? (mf/deref ts/comments-mode-ref)

        ;; On-page edit (P1.24): when ON, a full-canvas preview overlay
        ;; renders the published HTML in a sandboxed iframe with
        ;; contenteditable CMS-bound elements. Off by default — byte-
        ;; identical to the prior render when false (overlay emits nothing).
        on-page-edit-active? (mf/deref dope/on-page-edit-active)
        vbox           (mf/deref refs/vbox)
        zoom           (mf/deref refs/selected-zoom)

        ;; FIXME: pass this down to viewport and reuse it from here
        ;; instead of making an other deref on viewport for the same
        ;; data
        drawing
        (mf/deref refs/workspace-drawing)

        colorpalette?  (:colorpalette layout)
        textpalette?   (:textpalette layout)
        hide-ui?       (:hide-ui layout)

        exit-focus
        (mf/use-fn #(st/emit! (dw/toggle-focus-mode)))

        on-resize
        (mf/use-fn
         (mf/deps vport)
         (fn [resize-type size]
           (when (and vport (not= size vport))
             (st/emit! (dw/update-viewport-size resize-type size)
                       (dw/sync-wasm-workspace-viewport)))))

        on-resize-palette
        (mf/use-fn
         (fn [size]
           (reset! palete-size size)))

        node-ref (use-resize-observer on-resize)]
    [:*
     (when (not ^boolean hide-ui?)
       [:> palette* {:layout layout
                     :on-change-size on-resize-palette}])

     [:section
      {:key (dm/str "workspace-" page-id)
       :class (stl/css-case :workspace-content true
                            :focus-mode focus-mode?)
       :ref node-ref}

      [:section {:class (stl/css :workspace-viewport)}
       (when (dbg/enabled? :coordinates)
         [:> coordinates/coordinates* {:is-colorpalette colorpalette?}])

       (when (dbg/enabled? :history-overlay)
         [:div {:class (stl/css :history-debug-overlay)}
          [:button {:on-click #(st/emit! dw/reinitialize-undo)} "CLEAR"]
          [:> history-toolbox*]])

       [:> viewport*
        {:file file
         :page page
         :wglobal wglobal
         :selected selected
         :layout layout
         :palete-size
         (when (and (or colorpalette? textpalette?) (not hide-ui?))
           @palete-size)}]

       ;; P2.37 — on-screen comments overlay. Mounted ONLY when comments
       ;; mode is ON (off by default → byte-identical canvas). Renders coral
       ;; pins for page-floating + shape-anchored comments. Reduced-motion
       ;; safe (no transitions).
       (when (and ^boolean comments-mode? (not ^boolean hide-ui?))
         [:> comments-overlay*
          {:vbox vbox :vport vport :zoom zoom :page page}])

       ;; P1.24 — on-page edit preview overlay. Mounted ONLY when the
       ;; on-page-edit flag is ON (off by default → byte-identical canvas).
       ;; Full-canvas sandboxed iframe (position:fixed) — no viewport props.
       (when (and ^boolean on-page-edit-active? (not ^boolean hide-ui?))
         [:> on-page-preview*])

       ;; Floating "Exit focus" indicator — the only chrome overlay shown on
       ;; the canvas while focus mode is active. Reuses the existing
       ;; toggle-focus-mode event. Coral accent (#f28b82), Lucide focus icon
       ;; (stroke-width 2, currentColor). Hidden when focus mode is OFF.
       (when (and ^boolean focus-mode? (not ^boolean hide-ui?))
         [:button {:type "button"
                   :class (stl/css :focus-mode-exit-button)
                   :title (tr "workspace.focus.exit")
                   :aria-label (tr "workspace.focus.exit")
                   :on-click exit-focus}
          [:svg {:xmlns "http://www.w3.org/2000/svg"
                 :width "20"
                 :height "20"
                 :viewBox "0 0 24 24"
                 :fill "none"
                 :stroke "currentColor"
                 :stroke-width 2
                 :stroke-linecap "round"
                 :stroke-linejoin "round"
                 :aria-hidden "true"}
           [:path {:d "M3 7V5a2 2 0 0 1 2-2h2"}]
           [:path {:d "M17 3h2a2 2 0 0 1 2 2v2"}]
           [:path {:d "M21 17v2a2 2 0 0 1-2 2h-2"}]
           [:path {:d "M7 21H5a2 2 0 0 1-2-2v-2"}]]
          [:span {:class (stl/css :focus-mode-exit-label)}
           (tr "workspace.focus.exit")]])]]

     (when-not hide-ui?
       [:> sidebar* {:layout layout
                     ;; FIXME
                     :file-id (get file :id)
                     :page-id page-id
                     :file file
                     :selected selected
                     :section options-mode
                     :drawing-tool (get drawing :tool)
                     :focus-mode? focus-mode?}])]))

(mf/defc workspace-loader*
  {::mf/private true}
  []
  [:> loader*  {:title (tr "labels.loading")
                :class (stl/css :workspace-loader)
                :overlay true
                :file-loading true}])

(defn- make-team-ref
  [team-id]
  (l/derived (fn [state]
               (let [teams (get state :teams)]
                 (get teams team-id)))
             st/state))

(defn- make-file-ref
  [file-id]
  (l/derived (fn [state]
               ;; NOTE: for ensure ordering of execution, we need to
               ;; wait the file initialization completly success until
               ;; mark this file availablea and unlock the rendering
               ;; of the following components
               (when (= (get state :current-file-id) file-id)
                 (let [files (get state :files)
                       file  (get files file-id)]
                   (-> file
                       (dissoc :data)
                       (assoc ::has-data (contains? file :data))))))
             st/state
             =))

(defn- make-page-ref
  [file-id page-id]
  (l/derived (fn [state]
               (let [current-page-id (get state :current-page-id)]
                 ;; NOTE: for ensure ordering of execution, we need to
                 ;; wait the page initialization completly success until
                 ;; mark this file availablea and unlock the rendering
                 ;; of the following components
                 (when (= current-page-id page-id)
                   (dsh/lookup-page state file-id page-id))))
             st/state))

(mf/defc workspace-inner*
  {::mf/private true}
  [{:keys [page-id file-id file layout wglobal]}]
  (let [page-ref (mf/with-memo [file-id page-id]
                   (make-page-ref file-id page-id))
        page     (mf/deref page-ref)]

    (mf/with-effect []
      (let [focus-out #(st/emit! (dw/workspace-focus-lost))
            key       (events/listen globals/window "blur" focus-out)]
        (partial events/unlistenByKey key)))

    (mf/with-effect [file-id page-id]
      (st/emit! (dw/initialize-page file-id page-id))
      (fn []
        (st/emit! (dw/finalize-page file-id page-id))))

    (if (some? page)
      [:> workspace-content* {:file file
                              :page page
                              :wglobal wglobal
                              :layout layout}]
      [:> workspace-loader*])))

(mf/defc workspace*
  {::mf/wrap [mf/memo]}
  [{:keys [team-id project-id file-id page-id layout-name]}]

  (let [file-id          (hooks/use-equal-memo file-id)
        page-id          (hooks/use-equal-memo page-id)

        layout           (mf/deref refs/workspace-layout)
        wglobal          (mf/deref refs/workspace-global)

        team-ref         (mf/with-memo [team-id]
                           (make-team-ref team-id))
        file-ref         (mf/with-memo [file-id]
                           (make-file-ref file-id))

        team             (mf/deref team-ref)
        file             (mf/deref file-ref)

        file-loaded?     (get file ::has-data)

        file-name        (:name file)
        permissions      (:permissions team)

        read-only?       (mf/deref refs/workspace-read-only?)
        read-only?       (or read-only? (not (:can-edit permissions)))

        ;; Workshop (P1.35) — opt-in learning-center overlay. Off by
        ;; default; when false the workspace renders exactly as today.
        workshop-open?   (mf/deref refs/workshop-open?)

        design-tokens?   (features/use-feature "design-tokens/v1")

        wasm-renderer-enabled? (features/use-feature "render-wasm/v1")

        first-frame-rendered?  (mf/use-state false)

        background-color (:background-color wglobal)

        ;; Workshop entry button hover state (reduced-motion-safe: no
        ;; transition, just a swapped background on hover).
        ws-entry-hover? (mf/use-state false)

        toggle-workshop
        (mf/use-fn #(st/emit! (wsp/toggle-workshop)))

        on-ws-entry-enter
        (mf/use-fn #(reset! ws-entry-hover? true))

        on-ws-entry-leave
        (mf/use-fn #(reset! ws-entry-hover? false))

        ;; Comments mode (P2.37) — header toggle button state. Off by
        ;; default → canvas byte-identical. Coral Lucide message-circle.
        ;; Reduced-motion-safe: no transition, just a swapped background.
        comments-mode? (mf/deref ts/comments-mode-ref)
        comments-hover? (mf/use-state false)
        toggle-comments
        (mf/use-fn #(st/emit! (ts/toggle-comments-mode)))
        on-comments-enter
        (mf/use-fn #(reset! comments-hover? true))
        on-comments-leave
        (mf/use-fn #(reset! comments-hover? false))]

    (mf/with-effect []
      (st/emit! (dps/initialize-persistence)
                (dpl/update-plugins-permissions-peek)))

    ;; Setting the layout preset by its name
    (mf/with-effect [layout-name]
      (st/emit! (dw/initialize-workspace-layout layout-name)))

    (mf/with-effect [file-name]
      (when file-name
        (dom/set-html-title (tr "title.workspace" file-name))))

    (mf/with-effect [team-id file-id]
      (st/emit! (dw/initialize-workspace team-id file-id))
      (fn []
        (st/emit! ::dps/force-persist
                  (dw/finalize-workspace team-id file-id))))

    (mf/with-effect [file-id page-id file-loaded?]
      (when (and file-loaded? (not page-id))
        (st/emit! (dcm/go-to-workspace :file-id file-id ::rt/replace true))))

    (mf/with-effect [file-id page-id]
      (reset! first-frame-rendered? false))

    (mf/with-effect []
      (let [handle-wasm-render
            (fn [_]
              (reset! first-frame-rendered? true))
            listener-key (events/listen globals/document "penpot:wasm:render" handle-wasm-render)]
        (fn []
          (events/unlistenByKey listener-key))))

    [:> (mf/provider ctx/current-project-id) {:value project-id}
     [:> (mf/provider ctx/current-file-id) {:value file-id}
      [:> (mf/provider ctx/current-page-id) {:value page-id}
       [:> (mf/provider ctx/design-tokens) {:value design-tokens?}
        [:> (mf/provider ctx/workspace-read-only?) {:value read-only?}
         [:> modal-container*]
         [:section {:class (stl/css :workspace)
                    :style {:background-color background-color
                            :touch-action "none"
                            :position "relative"}}
          [:> context-menu*]
          (when (and file-loaded? page-id)
            [:> workspace-inner*
             {:page-id page-id
              :file-id file-id
              :file file
              :wglobal wglobal
              :layout layout}])
          ;; AI design bar (Feature 3 + 4) — floats above the viewport.
          (when (and file-loaded? page-id)
            [:> ai-bar*])
          ;; P2.37 — Comments mode header toggle button (top-right, left of
          ;; the Workshop button). A single unobtrusive header icon; emits
          ;; `toggle-comments-mode`. Coral Lucide message-circle. Inline
          ;; styles keep it SCSS-pipeline-free; hover swaps the background
          ;; (no transition = reduced-motion-safe). Active state (mode ON)
          ;; fills coral so the user sees it is armed.
          (when (and file-loaded? page-id)
            [:button
             {:type "button"
              :on-click toggle-comments
              :on-mouse-enter on-comments-enter
              :on-mouse-leave on-comments-leave
              :title (tr "workspace.comments.toggle")
              :aria-label (tr "workspace.comments.toggle")
              :aria-pressed (if ^boolean comments-mode? "true" "false")
              :style
              {:position "fixed"
               :top "56px"
               :right "54px"
               :width "34px"
               :height "34px"
               :border "none"
               :border-radius "10px"
               :cursor "pointer"
               :z-index 90
               :display "flex"
               :align-items "center"
               :justify-content "center"
               :background (cond
                             ^boolean comments-mode? "#f28b82"
                             @comments-hover? "rgba(242,139,130,0.20)"
                             :else "rgba(242,139,130,0.12)")
               :color (if (or ^boolean comments-mode? @comments-hover?)
                        "#ffffff" "#f28b82")
               :box-shadow (when (or ^boolean comments-mode? @comments-hover?)
                             "0 2px 8px rgba(242,139,130,0.4)")}}
             [:svg {:xmlns "http://www.w3.org/2000/svg"
                    :width "20" :height "20" :viewBox "0 0 24 24"
                    :fill "none" :stroke "currentColor" :stroke-width 2
                    :stroke-linecap "round" :stroke-linejoin "round"
                    :aria-hidden "true"}
              [:path {:d "M7.9 20A9 9 0 1 0 4 16.1L2 22Z"}]]])
          ;; Workshop (P1.35) — floating entry button (top-right). A single
          ;; unobtrusive header icon; emits `toggle-workshop`. Inline styles
          ;; keep it SCSS-pipeline-free; hover swaps the background (no
          ;; transition = reduced-motion-safe). Hidden while the overlay is
          ;; open (the overlay's own close button takes over).
          (when (and file-loaded? page-id (not ^boolean workshop-open?))
            [:button
             {:type "button"
              :on-click toggle-workshop
              :on-mouse-enter on-ws-entry-enter
              :on-mouse-leave on-ws-entry-leave
              :title (tr "workspace.workshop.open")
              :aria-label (tr "workspace.workshop.open")
              :style
              {:position "fixed"
               :top "56px"
               :right "14px"
               :width "34px"
               :height "34px"
               :border "none"
               :border-radius "10px"
               :cursor "pointer"
               :z-index 90
               :display "flex"
               :align-items "center"
               :justify-content "center"
               :background (if @ws-entry-hover? "#f28b82" "rgba(242,139,130,0.12)")
               :color (if @ws-entry-hover? "#ffffff" "#f28b82")
               :box-shadow (when @ws-entry-hover? "0 2px 8px rgba(242,139,130,0.4)")}}
             [:svg {:xmlns "http://www.w3.org/2000/svg"
                    :width "20" :height "20" :viewBox "0 0 24 24"
                    :fill "none" :stroke "currentColor" :stroke-width 2
                    :stroke-linecap "round" :stroke-linejoin "round"
                    :aria-hidden "true"}
              [:path {:d "M22 10v6M2 10l10-5 10 5-10 5z"}]
              [:path {:d "M6 12v5c3 3 9 3 12 0v-5"}]]])
          ;; Workshop overlay — rendered ONLY when open, so the closed path
          ;; emits nothing (byte-identical-when-inactive).
          (when ^boolean workshop-open?
            [:> workshop-panel*])
          (when (or (not (and file-loaded? page-id))
                    ;; in wasm renderer, extend the pixel loader until the first frame is rendered
                    ;; but do not apply it when switching pages
                    (and wasm-renderer-enabled?
                         (not file-loaded?)
                         (not @first-frame-rendered?)))
            [:> workspace-loader*])]]]]]]))

(mf/defc workspace-page*
  {::mf/lazy-load true}
  [props]
  [:> workspace* props])

