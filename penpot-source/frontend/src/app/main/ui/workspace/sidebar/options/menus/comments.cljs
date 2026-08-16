;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.comments
  "P2.37 — Comments inspector menu + webhook config + share-link (ALL_APPS_PARITY).

  Mounted in options.cljs (lead wires it). Takes `{:keys [shapes]}`:
    • when one+ shapes are selected, lists comments on the first selected
      shape (shape-anchored) + offers add/resolve/delete + per-comment
      'Send to <provider>';
    • when zero shapes are selected, lists page-floating comments on the
      current page + add/resolve/delete + per-comment Send.

  Also renders (always, regardless of selection):
    - Webhook config: provider dropdown (Slack/Teams/Jira/Confluence) +
      webhook URL input + Save. Held in localStorage
      (`ovion.webhook-url` / `ovion.webhook-provider`), key-gated like the
      Pexels key.
    - Share-link section: Generate button -> `https://ovion.app/share/<b64>`
      + Copy-to-clipboard + QR <img> via api.qrserver.com (no QR lib).

  Coral accent #f28b82, Lucide icons (message-circle / send / share-2 /
  link / check / trash-2), reduced-motion safe (no motion emitted)."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.workspace.team-sharing :as ts]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.util.i18n :refer [tr]]
   [clojure.string :as cstr]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

;; Coral + grey accents (Ovion theme) — matches the code-component menu.
(def ^:private coral "#f28b82")
(def ^:private grey  "#7d7d7d")

(def ^:private row-style
  {:display "flex" :align-items "center" :gap "8px" :width "100%"})

(def ^:private label-style
  {:font-size "11px" :color grey :width "72px" :flex-shrink "0"})

(def ^:private coral-btn-style
  {:display "inline-flex"
   :align-items "center"
   :justify-content "center"
   :height "26px"
   :padding "0 10px"
   :border-radius "6px"
   :border "1px solid rgba(242,139,130,0.4)"
   :background "rgba(242,139,130,0.08)"
   :color coral
   :cursor "pointer"
   :font-size "11px"
   :font-weight "500"})

(def ^:private ghost-btn-style
  (merge coral-btn-style
         {:border "1px solid rgba(125,125,125,0.3)"
          :background "transparent"
          :color grey}))

(def ^:private select-style
  {:flex "1"
   :height "26px"
   :border "1px solid rgba(125,125,125,0.3)"
   :border-radius "6px"
   :background "transparent"
   :color "inherit"
   :font-size "11px"
   :padding "0 6px"})

(def ^:private providers [:slack :teams :jira :confluence])

;; --- Lucide icons (lucide.dev, MIT) — 24x24, stroke currentColor, sw 2 ---

(defn- icon-message-circle
  []
  (mf/html
   [:svg {:width 14 :height 14 :viewBox "0 0 24 24" :fill "none"
          :stroke coral :stroke-width 2 :stroke-linecap "round"
          :stroke-linejoin "round" :style {:flex-shrink "0"}}
    [:path {:d "M7.9 20A9 9 0 1 0 4 16.1L2 22Z"}]]))

(defn- icon-send
  []
  (mf/html
   [:svg {:width 14 :height 14 :viewBox "0 0 24 24" :fill "none"
          :stroke coral :stroke-width 2 :stroke-linecap "round"
          :stroke-linejoin "round" :style {:flex-shrink "0"}}
    [:path {:d "M14.536 21.086a.5.5 0 0 0 .937-.024l6.5-19a.496.496 0 0 0-.635-.635l-19 6.5a.5.5 0 0 0-.024.937l7.93 3.18a2 2 0 0 1 1.112 1.11z"}]
    [:path {:d "m21.086 9.536-9.536 9.536"}]]))

(defn- icon-share
  []
  (mf/html
   [:svg {:width 14 :height 14 :viewBox "0 0 24 24" :fill "none"
          :stroke coral :stroke-width 2 :stroke-linecap "round"
          :stroke-linejoin "round" :style {:flex-shrink "0"}}
    [:circle {:cx "18" :cy "5" :r "3"}]
    [:circle {:cx "6" :cy "12" :r "3"}]
    [:circle {:cx "18" :cy "19" :r "3"}]
    [:line {:x1 "8.59" :y1 "13.51" :x2 "15.42" :y2 "17.49"}]
    [:line {:x1 "15.41" :y1 "6.51" :x2 "8.59" :y2 "10.49"}]]))

(defn- icon-link
  []
  (mf/html
   [:svg {:width 14 :height 14 :viewBox "0 0 24 24" :fill "none"
          :stroke coral :stroke-width 2 :stroke-linecap "round"
          :stroke-linejoin "round" :style {:flex-shrink "0"}}
    [:path {:d "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"}]
    [:path {:d "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"}]]))

(defn- icon-check
  []
  (mf/html
   [:svg {:width 14 :height 14 :viewBox "0 0 24 24" :fill "none"
          :stroke coral :stroke-width 2 :stroke-linecap "round"
          :stroke-linejoin "round" :style {:flex-shrink "0"}}
    [:path {:d "M20 6 9 17l-5-5"}]]))

(defn- icon-trash
  []
  (mf/html
   [:svg {:width 14 :height 14 :viewBox "0 0 24 24" :fill "none"
          :stroke coral :stroke-width 2 :stroke-linecap "round"
          :stroke-linejoin "round" :style {:flex-shrink "0"}}
    [:path {:d "M3 6h18"}]
    [:path {:d "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"}]
    [:line {:x1 10 :y1 11 :x2 10 :y2 17}]
    [:line {:x1 14 :y1 11 :x2 14 :y2 17}]]))

(defn- icon-copy
  []
  (mf/html
   [:svg {:width 14 :height 14 :viewBox "0 0 24 24" :fill "none"
          :stroke coral :stroke-width 2 :stroke-linecap "round"
          :stroke-linejoin "round" :style {:flex-shrink "0"}}
    [:rect {:x "9" :y "9" :width "13" :height "13" :rx "2" :ry "2"}]
    [:path {:d "M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"}]]))

;; --- Derived refs ----------------------------------------------------------

(defn- shape-comments-ref
  "Derived ref that reads the comments vector for `shape-id` from the
  workspace page objects, so the menu re-renders when comments change."
  [shape-id]
  (mf/with-memo [shape-id]
    (l/derived
     (fn [page]
       (let [shape (get-in page [:objects shape-id])]
         (ts/read-comments shape)))
     refs/workspace-page
     =)))

(defn- page-comments-ref
  "Derived ref over the current page's page-comments vector."
  []
  (mf/with-memo []
    (l/derived
     (fn [page]
       (ts/read-page-comments page))
     refs/workspace-page
     =)))

(def ^:private current-file-id-ref
  "Derived ref over the app-level `:current-file-id` slot, for the share-link
  generator. Defined here (not in refs.cljs) to keep P2.37 self-contained."
  (l/derived :current-file-id st/state))

;; --- Webhook config section ------------------------------------------------

(mf/defc webhook-config*
  {::mf/private true}
  []
  (let [url*      (mf/use-state (ts/load-webhook-url))
        provider* (mf/use-state (ts/load-webhook-provider))
        saved*    (mf/use-state false)
        on-url    (mf/use-fn #(reset! url* (.. % -target -value)))
        on-prov   (mf/use-fn #(reset! provider* (keyword (.. % -target -value))))
        on-save
        (mf/use-fn
         (mf/deps @url* @provider*)
         (fn []
           (ts/save-webhook-url @url*)
           (ts/save-webhook-provider @provider*)
           (reset! saved* true)
           (js/setTimeout #(reset! saved* false) 1500)))]
    [:div {:style {:display "flex" :flex-direction "column" :gap "6px"
                   :padding "6px 0"}}
     [:div {:style row-style}
      [:span {:style label-style}
       (tr "workspace.options.comments.provider")]
      [:select {:value (str (or @provider* :slack))
                :style select-style
                :on-change on-prov}
       (for [p providers]
         ^{:key (str p)}
         [:option {:value (str p)} (str p)])]]
     [:div {:style row-style}
      [:span {:style label-style}
       (tr "workspace.options.comments.webhook-url")]
      [:input {:type "text"
               :value (or @url* "")
               :placeholder "https://hooks.slack.com/…"
               :class (stl/css :type-input)
               :style {:flex "1"}
               :on-change on-url}]]
     [:div {:style (merge row-style {:justify-content "flex-end" :gap "6px"})}
      (when @saved*
        [:span {:style {:font-size "11px" :color coral :display "inline-flex"
                        :align-items "center" :gap "4px"}}
         (icon-check)
         [:span (tr "workspace.options.comments.saved")]])
      [:button {:type "button" :style coral-btn-style :on-click on-save}
       (tr "workspace.options.comments.save")]]]))

;; --- Share-link section ----------------------------------------------------

(mf/defc share-link-section*
  {::mf/private true}
  [{:keys [file-id]}]
  (let [link*    (mf/use-state nil)
        copied*  (mf/use-state false)
        on-generate
        (mf/use-fn
         (mf/deps file-id)
         #(reset! link* (ts/generate-share-link file-id)))
        on-copy
        (mf/use-fn
         (mf/deps @link*)
         (fn []
           (try
             (-> js/navigator .-clipboard (.writeText (str @link*))
                 (.then (fn [] (reset! copied* true)
                          (js/setTimeout #(reset! copied* false) 1500))))
             (catch :default _))))]
    [:div {:style {:display "flex" :flex-direction "column" :gap "6px"
                   :padding "6px 0"}}
     [:div {:style row-style}
      [:button {:type "button" :style coral-btn-style :on-click on-generate}
       (icon-share)
       [:span {:style {:margin-left "4px"}}
        (tr "workspace.options.comments.generate-link")]]]
     (when-let [link @link*]
       [:*
        [:div {:style row-style}
         [:span {:style label-style}
          (tr "workspace.options.comments.link")]
         [:input {:type "text"
                  :value link
                  :readOnly true
                  :class (stl/css :type-input)
                  :style {:flex "1" :font-size "10px"}
                  :on-click #(.. % -target select)}]
         [:button {:type "button"
                   :style (merge ghost-btn-style {:padding "0 8px"
                                                   :flex-shrink "0"})
                   :title (tr "workspace.options.comments.copy")
                   :on-click on-copy}
          (if @copied* (icon-check) (icon-copy))]]
        [:div {:style {:display "flex" :justify-content "center" :padding "4px 0"}}
         [:img
          {:src (ts/qr-image-url link 140)
           :alt (tr "workspace.options.comments.qr-alt")
           :width 140 :height 140
           :style {:border (dm/str "1px solid rgba(125,125,125,0.2)")
                   :border-radius "6px"}}]]])]))

;; --- Comment row -----------------------------------------------------------

(mf/defc comment-row*
  {::mf/private true}
  [{:keys [comment shape-id on-resolve on-delete on-send]}]
  (let [resolved? (boolean (:resolved? comment))
        sending*  (mf/use-state false)
        sent*     (mf/use-state false)
        err*      (mf/use-state nil)
        on-send-fn
        (mf/use-fn
         (mf/deps comment shape-id)
         (fn []
           (reset! sending* true)
           (reset! err* nil)
           (-> (ts/post-comment-to-webhook
                (assoc comment :shape-id shape-id))
               (.then (fn [_]
                        (reset! sending* false)
                        (reset! sent* true)
                        (js/setTimeout #(reset! sent* false) 1500)))
               (.catch (fn [err]
                         (reset! sending* false)
                         (reset! err* (str err)))))))]
    [:div
     {:style
      {:border (dm/str "1px solid " (if resolved? "rgba(242,139,130,0.2)" "rgba(125,125,125,0.2)"))
       :border-radius "6px" :padding "8px"
       :display "flex" :flex-direction "column" :gap "6px"
       :opacity (if resolved? "0.6" "1")}}
     [:div {:style {:font-size "11px" :color grey :display "flex"
                    :justify-content "space-between" :align-items "center"}}
      [:span (:author comment (tr "workspace.comments.anonymous"))]
      (when resolved?
        [:span {:style {:color coral :display "inline-flex" :align-items "center"
                        :gap "3px"}}
         (icon-check)
         [:span (tr "workspace.comments.resolved")]])]
     [:div {:style {:font-size "12px" :white-space "pre-wrap"
                    :word-break "break-word" :color "#1a1a1a"}}
      (:body comment "")]
     [:div {:style {:display "flex" :gap "6px" :flex-wrap "wrap"}}
      [:button {:type "button" :style (merge ghost-btn-style {:padding "0 8px"})
                :on-click on-resolve}
       (icon-check)
       [:span {:style {:margin-left "4px"}}
        (if resolved?
          (tr "workspace.comments.unresolve")
          (tr "workspace.comments.resolve"))]]
      [:button {:type "button" :style (merge ghost-btn-style {:padding "0 8px"})
                :on-click on-delete}
       (icon-trash)
       [:span {:style {:margin-left "4px"}}
        (tr "workspace.comments.delete")]]
      [:button {:type "button"
                :style (merge coral-btn-style
                              {:padding "0 8px"
                               :background (if @sent* "rgba(242,139,130,0.2)" coral)})
                :disabled @sending*
                :on-click on-send-fn}
       (icon-send)
       [:span {:style {:margin-left "4px"}}
        (cond
          @sending* (tr "workspace.options.comments.sending")
          @sent*    (tr "workspace.options.comments.sent")
          :else     (tr "workspace.comments.send"))]]]
     (when-let [err @err*]
       [:div {:style {:font-size "11px" :color "#c0392b"}}
        (cond
          (= err "webhook-url-missing")
          (tr "workspace.options.comments.webhook-url-missing")
          (= err "webhook-post-failed")
          (tr "workspace.options.comments.webhook-post-failed")
          :else (tr "workspace.options.comments.webhook-post-failed"))])]))

;; --- Add-comment form ------------------------------------------------------

(mf/defc add-comment-form*
  {::mf/private true}
  [{:keys [shape-id author on-added]}]
  (let [body*  (mf/use-state "")
        on-body (mf/use-fn #(reset! body* (.. % -target -value)))
        on-add
        (mf/use-fn
         (mf/deps @body* shape-id author on-added)
         (fn []
           (let [body (cstr/trim (str @body*))]
             (when (not (empty? body))
               ;; x/y default to 0 for shape-anchored comments added from
               ;; the inspector (the pin sits at the shape's top-left).
               (if shape-id
                 (st/emit! (ts/add-comment-event
                            shape-id
                            (ts/new-comment author body 0 0)))
                 (st/emit! (ts/add-page-comment-event
                            (ts/new-comment author body 0 0))))
               (on-added))
             (reset! body* ""))))]
    [:div {:style row-style}
     [:input {:type "text"
              :value @body*
              :placeholder (tr "workspace.options.comments.add-placeholder")
              :class (stl/css :type-input)
              :style {:flex "1"}
              :on-change on-body
              :on-key-down (mf/use-fn
                            (mf/deps on-add)
                            (fn [e]
                              (when (= (.. e -key) "Enter")
                                (.preventDefault e)
                                (on-add))))}]
     [:button {:type "button" :style (merge coral-btn-style {:padding "0 10px"
                                                              :flex-shrink "0"})
               :on-click on-add}
      (icon-send)]]))

;; --- Main menu -------------------------------------------------------------

(mf/defc comments-menu*
  "Inspector menu for comments + webhook config + share-link. `shapes` is
  the vector of currently selected shapes. When one+ shapes are selected,
  comments on the first selected shape are listed; when zero are selected,
  page-floating comments are listed. Webhook config + share-link are always
  shown."
  [{:keys [shapes]}]
  (let [shape      (first shapes)
        shape-id   (:id shape)
        selected?  (some? shape-id)

        comments   (mf/deref
                    (if selected?
                      (shape-comments-ref shape-id)
                      (page-comments-ref)))

        file-id    (mf/deref current-file-id-ref)
        author*    (mf/use-state "")
        on-author  (mf/use-fn #(reset! author* (.. % -target -value)))]

    [:div {:class (stl/css :element-set)}
     [:div {:class (stl/css :element-title)}
      [:> title-bar* {:collapsable true :collapsed false
                      :title (tr "workspace.options.comments.title")}
       [:span {:style {:display "inline-flex" :align-items "center"
                       :margin-left "6px"}}
        (icon-message-circle)]]]

     [:div {:class (stl/css :element-set-content)}
      [:div {:class (stl/css :element-group)
             :style {:display "flex" :flex-direction "column" :gap "8px"}}

       ;; Author input (shared by add-comment + Send).
       [:div {:style row-style}
        [:span {:style label-style}
         (tr "workspace.comments.author")]
        [:input {:type "text"
                 :value @author*
                 :placeholder (tr "workspace.comments.author-placeholder")
                 :class (stl/css :type-input)
                 :style {:flex "1"}
                 :on-change on-author}]]

       ;; Comments list.
       (if (seq comments)
         [:div {:style {:display "flex" :flex-direction "column" :gap "6px"}}
          (for [c comments]
            ^{:key (:id c)}
            [:> comment-row*
             {:comment c
              :shape-id (when selected? shape-id)
              :on-resolve
              (mf/use-fn
               (mf/deps shape-id (:id c))
               (if selected?
                 #(st/emit! (ts/resolve-comment-event shape-id (:id c)))
                 #(st/emit! (ts/resolve-page-comment-event (:id c)))))
              :on-delete
              (mf/use-fn
               (mf/deps shape-id (:id c))
               (if selected?
                 #(st/emit! (ts/delete-comment-event shape-id (:id c)))
                 #(st/emit! (ts/delete-page-comment-event (:id c)))))}])]
         [:div {:style {:font-size "11px" :color grey :padding "4px 0"}}
          (tr "workspace.options.comments.none")])

       ;; Add comment.
       [:> add-comment-form*
        {:shape-id (when selected? shape-id)
         :author (cstr/trim (or @author* ""))
         :on-added #()}]

       ;; Webhook config.
       [:div {:style {:border-top "1px solid rgba(125,125,125,0.15)"
                      :margin-top "4px" :padding-top "6px"}}
        [:div {:style {:font-size "11px" :color grey :margin-bottom "4px"
                       :display "flex" :align-items "center" :gap "4px"}}
         (icon-link)
         [:span (tr "workspace.options.comments.webhook-section")]]
        [:> webhook-config*]]

       ;; Share-link section.
       [:div {:style {:border-top "1px solid rgba(125,125,125,0.15)"
                      :margin-top "4px" :padding-top "6px"}}
        [:div {:style {:font-size "11px" :color grey :margin-bottom "4px"
                       :display "flex" :align-items "center" :gap "4px"}}
         (icon-share)
         [:span (tr "workspace.options.comments.share-section")]]
        [:> share-link-section* {:file-id file-id}]]]]]))