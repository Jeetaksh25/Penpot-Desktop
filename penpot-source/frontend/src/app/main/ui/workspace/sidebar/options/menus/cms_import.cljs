;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.cms-import
  "CMS platform importer menu (ALL_APPS_PARITY P2.05).

  Sidebar panel for importing a remote CMS (WordPress / Webflow /
  Contentful) into Ovion CMS collections on the current page. The user
  picks a platform, enters the CMS base URL (+ optional token for
  authenticated endpoints), and clicks Import; `data.workspace.cms-import`
  fires the Rust `import_cms_platform` command and builds real Ovion
  collections with populated items.

  Visual world: reuses the shared `.element-set` / `.element-title` /
  `.element-set-content` grid classes the sibling `cms.cljs` menu uses,
  plus a scoped injected `<style>` block (mirrors `ui.workspace.publish`)
  pinned to the AI reference tokens (coral #f28b82, grey #7d7d7d, Helvetica
  Now Display) so the panel renders identically with or without the
  `cms_import.scss` build step. Lucide glyphs are inlined (viewBox
  \"0 0 24 24\", stroke-width 2, currentColor). Reduced-motion guard on
  the single transient spinner animation. i18n keys are placeholders —
  the lead adds them to en.po."

  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.workspace.cms-import :as cmsi]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.hiccup :as hic]
   [app.util.i18n :as i18n :refer [tr]]
   [clojure.string :as cstr]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

;; ── Inline Lucide icons (viewBox 0 0 24 24, stroke-width 2, currentColor) ──

(defn- lucide
  "Wrap a seq of SVG children in a Lucide 24×24 icon frame."
  [body]
  (hic/el (into [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
                       :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
                       :aria-hidden "true"
                       :style {:width "16px" :height "16px" :flex-shrink "0"}}]
                body)))

(defn- icon-download
  "Lucide `download` — coral accent on the section header."
  []
  (lucide [[:path {:d "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"}]
           [:polyline {:points "7 10 12 15 17 10"}]
           [:line {:x1 12 :y1 15 :x2 12 :y2 3}]]))

(defn- icon-spinner
  "Lucide `loader-2` — animated spinner shown while importing."
  []
  (lucide [[:path {:d "M21 12a9 9 0 1 1-6.219-8.56"}]]))

;; ── Inline styles (AI reference tokens; no SCSS dependency) ─────────────────

(def ^:private coral "#f28b82")
(def ^:private grey "#7d7d7d")
(def ^:private danger "#e5484d")
(def ^:private font-stack
  "'Helvetica Now Display', 'Helvetica Now', Helvetica, Arial, sans-serif")

(def ^:private style-injected
  ;; One injected <style> block, scoped under `.cmsi-root`. Reduced-motion
  ;; guard kills the single transient spinner animation.
  (str
   ".cmsi-root { font-family: " font-stack "; color: #1a1a1a; }"
   ".cmsi-row { display: flex; flex-direction: column; gap: 6px; }"
   ".cmsi-field { display: flex; flex-direction: column; gap: 3px; }"
   ".cmsi-label { font-size: 11px; font-weight: 500; color: " grey "; }"
   ".cmsi-input { font-family: " font-stack "; font-size: 12px; "
   "  padding: 7px 9px; border: 1px solid #ececec; border-radius: 6px; "
   "  color: #1a1a1a; background: #fff; outline: none; width: 100%; "
   "  box-sizing: border-box; transition: border-color .15s ease; }"
   ".cmsi-input:focus { border-color: " coral "; }"
   ".cmsi-input:disabled { background: #f7f7f7; color: " grey "; cursor: not-allowed; }"
   ".cmsi-select { font-family: " font-stack "; font-size: 12px; "
   "  padding: 7px 9px; border: 1px solid #ececec; border-radius: 6px; "
   "  color: #1a1a1a; background: #fff; outline: none; cursor: pointer; }"
   ".cmsi-btn { display: inline-flex; align-items: center; justify-content: center; "
   "  gap: 6px; width: 100%; padding: 8px 10px; border: none; border-radius: 6px; "
   "  background: " coral "; color: #fff; font-family: " font-stack "; "
   "  font-size: 12px; font-weight: 600; cursor: pointer; "
   "  transition: box-shadow .15s ease; }"
   ".cmsi-btn:hover { box-shadow: 0 0 0 3px " coral "33; }"
   ".cmsi-btn:disabled { background: #ccc; color: #fff; cursor: not-allowed; box-shadow: none; }"
   ".cmsi-btn svg { width: 14px; height: 14px; }"
   ".cmsi-status { font-size: 11px; line-height: 1.5; margin-top: 6px; }"
   ".cmsi-status-progress { display: flex; align-items: center; gap: 6px; color: " grey "; }"
   ".cmsi-status-ok { color: #1a9f62; }"
   ".cmsi-status-err { color: " danger "; }"
   ".cmsi-spin { animation: cmsi-spin 1s linear infinite; }"
   "@keyframes cmsi-spin { to { transform: rotate(360deg); } }"
   ".cmsi-hint { font-size: 11px; color: " grey "; line-height: 1.4; margin-top: 6px; }"
   "@media (prefers-reduced-motion: reduce) {"
   "  .cmsi-btn, .cmsi-input { transition: none; }"
   "  .cmsi-spin { animation: none; }"
   "}"))

;; ── Platform options ───────────────────────────────────────────────────────

(def ^:private platforms
  "The selectable CMS platforms. WordPress is enabled; Webflow and
  Contentful are shown but disabled with a 'coming soon' marker (their
  Rust fetchers honestly return `Err` until wired)."
  [{:id "wordpress"  :label "WordPress"  :disabled? false}
   {:id "webflow"    :label "Webflow"    :disabled? true}
   {:id "contentful" :label "Contentful" :disabled? true}])

;; ── Reactive import-status ref ─────────────────────────────────────────────
;; Local derived ref over the potok state (same pattern as
;; `cms.cljs`'s local `cms-data` ref). Kept here so `refs.cljs` stays
;; untouched. Updated by the `cms-import-progress` / `-succeeded` /
;; `-failed` events emitted by the data layer.

(def cms-import-status
  (l/derived (fn [state] (get-in state [:cms-import :status]))
             st/state))

;; ── Status line (progress / success / error) ────────────────────────────────

(mf/defc cms-import-status*
  {::mf/wrap [mf/memo]}
  [{:keys [status]}]
  (when status
    (let [st (:state status)]
      (cond
        (= st :progress)
        [:div {:class "cmsi-status cmsi-status-progress"}
         [:span {:class "cmsi-spin"} (icon-spinner)]
         [:span (:message status)]]

        (= st :succeeded)
        (let [{:keys [platform collections items]} (:summary status)]
          [:div {:class "cmsi-status cmsi-status-ok"}
           (tr "workspace.options.cms-import.success"
               collections items (or platform "wordpress"))])

        (= st :failed)
        [:div {:class "cmsi-status cmsi-status-err"}
         (:error status)]))))

;; ── Menu ───────────────────────────────────────────────────────────────────

(mf/defc cms-import-menu*
  {::mf/wrap [mf/memo]}
  [{:keys [shapes]}]
  ;; Mounted on frames by the lead (alongside `cms-menu*`). Only render
  ;; when a frame is selected — `shapes` is the prop the options panel
  ;; passes (the lead wires the exact same `{:keys [shapes]}` it passes
  ;; to `cms-menu*`).
  (when (seq shapes)
    (let [open?*    (mf/use-state true)
          open?     (deref open?*)
          toggle    (mf/use-fn #(swap! open?* not))

          platform* (mf/use-state "wordpress")
          platform  (deref platform*)
          base-url* (mf/use-state "")
          base-url  (deref base-url*)
          token*    (mf/use-state "")
          token     (deref token*)

          on-platform (mf/use-fn #(reset! platform* (.. % -target -value)))
          on-base-url (mf/use-fn #(reset! base-url* (.. % -target -value)))
          on-token    (mf/use-fn #(reset! token* (.. % -target -value)))

          on-import
          (mf/use-fn
           (mf/deps platform base-url token)
           (fn []
             (when (and (seq (cstr/trim base-url)) (not= platform ""))
               (st/emit! (cmsi/run-cms-import
                          {:platform platform
                           :base-url (cstr/trim base-url)
                           :token    (let [t (cstr/trim token)]
                                       (when (seq t) t))})))))

          status      (mf/deref cms-import-status)
          importing?  (and (some? status) (= (:state status) :progress))
          url-empty?  (empty? (cstr/trim base-url))]

      [:div {:class (stl/css :element-set)}
       [:div {:class (stl/css :element-title)}
        [:> title-bar* {:collapsable true
                        :collapsed (not open?)
                        :on-collapsed toggle
                        :title (tr "workspace.options.cms-import.section")}
         [:& icon-download]]]

       (when open?
         [:div {:class (stl/css :element-set-content)}
          [:style {:dangerouslySetInnerHTML #js {:__html style-injected}}]
          [:div {:class "cmsi-root cmsi-row"}

           ;; Platform selector
           [:div {:class "cmsi-field"}
            [:label {:class "cmsi-label"}
             (tr "workspace.options.cms-import.platform")]
            [:select {:class "cmsi-select"
                      :value platform
                      :disabled importing?
                      :on-change on-platform}
             (for [p platforms]
               [:option {:key (:id p)
                         :value (:id p)
                         :disabled (:disabled? p)}
                (str (:label p)
                     (when (:disabled? p)
                       (str " — "
                            (tr "workspace.options.cms-import.coming-soon"))))])]]

           ;; Base URL
           [:div {:class "cmsi-field"}
            [:label {:class "cmsi-label"}
             (tr "workspace.options.cms-import.base-url")]
            [:input {:class "cmsi-input"
                     :type "text"
                     :value base-url
                     :placeholder "https://your-site.com"
                     :disabled importing?
                     :on-change on-base-url}]]

           ;; Optional token
           [:div {:class "cmsi-field"}
            [:label {:class "cmsi-label"}
             (tr "workspace.options.cms-import.token")]
            [:input {:class "cmsi-input"
                     :type "password"
                     :value token
                     :placeholder (tr "workspace.options.cms-import.token-placeholder")
                     :disabled importing?
                     :on-change on-token}]]

           ;; Import button
           [:button {:class "cmsi-btn"
                     :type "button"
                     :disabled (or importing? url-empty?)
                     :on-click on-import}
            (when importing? [:span {:class "cmsi-spin"} (icon-spinner)])
            (tr (if importing?
                  "workspace.options.cms-import.importing"
                  "workspace.options.cms-import.import"))]

           ;; Status / result
           [:& cms-import-status* {:status status}]

           ;; Hint
           [:div {:class "cmsi-hint"}
            (tr "workspace.options.cms-import.hint")]]])])))