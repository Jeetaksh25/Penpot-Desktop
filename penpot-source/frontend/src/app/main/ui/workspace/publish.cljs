;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.publish
  "Ovion Cloud publish modal (ALL_APPS_PARITY P0.11).

  Registered as `modal/components ::modal/register-as :publish` and opened
  by the lead-wired Publish menu item via `modal/show!`. Generates a static
  HTML bundle from the current page (reusing `data.exports.publish/build-bundle-from-page`)
  and uploads it to Ovion Cloud through the Rust `publish_site` command.

  The modal OWNS the `invoke` for immediate UI feedback (it does not route
  through the `publish-current-site` store event): on Publish it builds the
  bundle, calls `invoke \"publish_site\"`, and on success sets the local
  `share-url*` state to the returned URL; on error sets `error*`. This mirrors
  the `data.workspace.ai-gen/generate-design` detached-promise pattern
  (`p/then` / `p/catch` on the invoke promise) but updates local modal state
  instead of emitting potok events.

  Token: the frontend never sees the raw Ovion Cloud token (`llm_get_config`
  masks it to a `*_set` bool), so the modal passes `nil` for `token` and Rust
  resolves it from `<app-data>/llm.json`. On mount the modal fetches
  `llm_get_config` only to surface a 'not configured' hint
  (`ovion_cloud_token_set` false) before the user clicks Publish.

  Visual world: reuses the global modal SCSS classes (`:modal-overlay`,
  `:modal-container`, `:modal-header`, `:modal-title`, `:modal-content`,
  `:modal-footer`, `:action-buttons`, `:cancel-button`, `:accept-btn`,
  `:primary`) — the same set `rotate-copies` uses — so no new SCSS is needed.
  Custom publish elements (share-url input, open link, copy button, error
  text) are styled with inline styles pinned to the AI reference tokens
  (coral #f28b82, grey #7d7d7d, Helvetica Now Display font stack). Reduced
  motion is honored via a `prefers-reduced-motion` media query on the single
  transient animation (the copy 'copied' tick). Lucide glyphs are inlined
  (viewBox \"0 0 24 24\", stroke-width 2, currentColor)."
  (:require-macros [app.main.style :as stl])
  (:require
   ["@tauri-apps/api/core" :refer [invoke]]
   [app.main.data.exports.publish :as pub]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.ai-gen :as ai]
   [app.main.ui.workspace.ai-design :as ad]
   [app.main.refs :as refs]
   [app.util.clipboard :as clipboard]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.timers :as tm]
   [promesa.core :as p]
   [rumext.v2 :as mf]))

;; ── Lucide icons (inline, 24×24, stroke-width 2, currentColor) ──────────────

(defn- li
  "Wrap a seq of SVG children in a Lucide 24×24 icon frame (matches ai_bar).
  Returns a real React element (via `ad/icon-el`), not a hiccup vector — a
  bare vector as a React child throws Minified #31. Children spliced with
  `into` so `icon-el` sees each path as a child."
  [body]
  (ad/icon-el
   (into [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
                :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
                :aria-hidden "true"
                :style {:width "16px" :height "16px" :vertical-align "middle"}}]
         body)))

(def ^:private lucide-copy
  (li [[:rect {:x 9 :y 9 :width 13 :height 13 :rx 2 :ry 2}]
       [:path {:d "M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"}]]))

(def ^:private lucide-external-link
  (li [[:path {:d "M15 3h6v6"}]
       [:path {:d "M10 14 21 3"}]
       [:path {:d "M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"}]]))

(def ^:private lucide-cloud-upload
  (li [[:path {:d "M12 13v8"}]
       [:path {:d "m4 14 8-7 8 7"}]
       [:path {:d "M20 17.5A4.5 4.5 0 0 0 16.5 9h-1.8A7 7 0 1 0 4 15.3"}]]))

;; ── Inline styles (AI reference tokens; no SCSS dependency) ──────────────────

(def ^:private coral "#f28b82")
(def ^:private grey "#7d7d7d")
(def ^:private danger "#e5484d")
(def ^:private font-stack
  "'Helvetica Now Display', 'Helvetica Now', Helvetica, Arial, sans-serif")

(def ^:private style-injected
  ;; One injected <style> block, scoped under `.pub-root`. Includes a
  ;; reduced-motion guard for the single transient animation (copied tick).
  (str
   ".pub-root { font-family: " font-stack "; color: #1a1a1a; }"
   ".pub-desc { font-size: 13px; line-height: 1.5; color: " grey "; margin: 0 0 14px; }"
   ".pub-url-row { display: flex; align-items: center; gap: 8px; margin-top: 12px; }"
   ".pub-url-input { flex: 1; font-family: " font-stack "; font-size: 13px; "
   "  padding: 8px 10px; border: 1px solid #ececec; border-radius: 6px; "
   "  color: #1a1a1a; background: #fff; outline: none; }"
   ".pub-url-input:focus { border-color: " coral "; }"
   ".pub-link { display: inline-flex; align-items: center; gap: 4px; "
   "  font-size: 13px; color: " coral "; text-decoration: none; font-weight: 600; }"
   ".pub-link:hover { text-decoration: underline; }"
   ".pub-copy { display: inline-flex; align-items: center; justify-content: center; "
   "  width: 34px; height: 34px; border: 1px solid #ececec; border-radius: 6px; "
   "  background: #fff; color: #1a1a1a; cursor: pointer; transition: box-shadow .15s ease; }"
   ".pub-copy:hover { box-shadow: 0 0 0 2px " coral "33; }"
   ".pub-copy.copied { color: " coral "; border-color: " coral "; }"
   ".pub-error { font-size: 13px; color: " danger "; margin-top: 12px; line-height: 1.5; }"
   ".pub-hint { font-size: 12px; color: " grey "; margin-top: 10px; }"
   ".pub-pub-btn { display: inline-flex; align-items: center; gap: 6px; }"
   ".pub-pub-btn svg { width: 14px; height: 14px; }"
   ".pub-spin { animation: pub-spin 1s linear infinite; }"
   "@keyframes pub-spin { to { transform: rotate(360deg); } }"
   "@media (prefers-reduced-motion: reduce) {"
   "  .pub-copy { transition: none; }"
   "  .pub-spin { animation: none; }"
   "}"))

;; ── Modal ────────────────────────────────────────────────────────────────────

(mf/defc publish-modal
  {::mf/register modal/components
   ::mf/register-as :publish}
  []
  (let [page        (mf/deref refs/workspace-page)
        publishing* (mf/use-state false)
        share-url*  (mf/use-state nil)
        error*      (mf/use-state nil)
        copied*     (mf/use-state false)
        config*     (mf/use-state nil)  ;; {:token-set bool :endpoint str}

        cancel!
        (mf/use-fn
         (fn [_]
           (modal/hide!)))

        do-copy
        (mf/use-fn
         (mf/deps @share-url*)
         (fn [_]
           (when (some? @share-url*)
             (clipboard/to-clipboard @share-url*)
             (reset! copied* true)
             (tm/schedule 1400 #(reset! copied* false)))))

        on-publish
        (mf/use-fn
         (mf/deps page)
         (fn [_]
           (let [bundle (pub/build-bundle-from-page page)]
             (cond
               (nil? bundle)
               (reset! error* (tr "modals.publish.empty-page"))

               :else
               (do
                 (reset! publishing* true)
                 (reset! error* nil)
                 (let [request (clj->js {:bundle   bundle
                                         :token    nil
                                         :endpoint nil})]
                   ;; Detached promise — updates local modal state on resolve.
                   ;; Mirrors ai_gen.cljs generate-design (~line 646):
                   ;;   (-> (invoke-generate request) (p/then handle) (p/catch handle-err))
                   (-> (invoke "publish_site" #js {:request request})
                       (p/then (fn [result]
                                 (let [res (js->clj result :keywordize-keys true)]
                                   (reset! share-url* (:share_url res))
                                   (reset! publishing* false))))
                       (p/catch (fn [e]
                                  (reset! error* (str e))
                                  (reset! publishing* false))))))))))]

    ;; Load config once on mount — only to surface the 'not configured' hint.
    ;; The actual token is never read here; Rust resolves it from llm.json.
    (mf/with-effect
      []
      (-> (ai/invoke-get-config)
          (p/then (fn [res]
                    (let [cfg (js->clj res :keywordize-keys true)]
                      (reset! config*
                              {:token-set (boolean (:ovion_cloud_token_set cfg))
                               :endpoint  (:ovion_cloud_endpoint cfg "")}))))
          (p/catch (fn [_] (reset! config* {:token-set true :endpoint ""})))))

    (let [publishing?  @publishing*
          share-url    @share-url*
          err          @error*
          cfg          @config*
          token-set?   (get cfg :token-set true)
          accept-label (if publishing?
                         (tr "modals.publish.publishing")
                         (tr "modals.publish.publish"))]

      [:div {:class (stl/css :modal-overlay)}
       [:div {:class (stl/css :modal-container)}
        [:div {:class (stl/css :modal-header)}
         [:h2 {:class (stl/css :modal-title)}
          [:span {:class "pub-pub-btn"} lucide-cloud-upload
           [:span {:style {:margin-left "4px"}} (tr "modals.publish.title")]]]
         [:button {:class (stl/css :modal-close-btn)
                   :on-click cancel!} "×"]]

        [:div {:class (stl/css :modal-content)}
         [:style {:dangerouslySetInnerHTML
                  #js {:__html style-injected}}]
         [:div {:class "pub-root"}
          [:p {:class "pub-desc"} (tr "modals.publish.description")]

          (when (and (some? cfg) (not token-set?))
            [:p {:class "pub-hint"} (tr "modals.publish.not-configured")])

          (when (some? err)
            [:p {:class "pub-error"} err])

          (when (some? share-url)
            [:div {:class "pub-url-row"}
             [:input {:class "pub-url-input"
                      :type "text"
                      :readOnly true
                      :value share-url
                      :aria-label (tr "modals.publish.share-url")}]
             [:a {:class "pub-link"
                  :href share-url
                  :target "_blank"
                  :rel "noopener noreferrer"}
              lucide-external-link
              [:span {:style {:margin-left "2px"}}
               (tr "modals.publish.open")]]
             [:button {:class (str "pub-copy" (when @copied* " copied"))
                       :type "button"
                       :aria-label (tr "modals.publish.copy")
                       :title (if @copied*
                                (tr "modals.publish.copied")
                                (tr "modals.publish.copy"))
                       :on-click do-copy}
              lucide-copy]])]]

        [:div {:class (stl/css :modal-footer)}
         [:div {:class (stl/css :action-buttons)}
          [:input {:class (stl/css :cancel-button)
                   :type "button"
                   :value (tr "modals.publish.cancel")
                   :on-click cancel!}]
          [:input {:class (stl/css-case :accept-btn true
                                        :primary true)
                   :type "button"
                   :value accept-label
                   :disabled publishing?
                   :on-click on-publish}]]]]])))