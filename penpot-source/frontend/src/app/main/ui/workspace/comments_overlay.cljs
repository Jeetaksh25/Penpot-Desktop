;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.comments-overlay
  "P2.37 — On-screen comments canvas overlay (ALL_APPS_PARITY).

  Renders coral comment pins on the workspace canvas when comments mode is
  ON. When OFF (the default) this component renders NOTHING — the canvas is
  byte-identical to today (byte-identical-when-inactive).

  A pin is drawn for every comment on the current page:
    • page-floating comments (stored on the page's `:ovion \"page-comments\"`
      slot) — positioned at the comment's page (x,y).
    • shape-anchored comments (stored on each shape's `:ovion \"comments\"`
      slot) — also positioned at the comment's page (x,y); the shape-id is
      carried so the popover can resolve/delete via the shape slot.

  Page -> screen transform mirrors `viewport/comments.cljs`:
    pos-x = (* (- vbox-x) zoom), pos-y = (* (- vbox-y) zoom)
    screen-x = (* (- page-x vbox-x) zoom), screen-y = (* (- page-y vbox-y) zoom)
  We render an outer vport-sized container, an inner `translate(pos-x,pos-y)`
  div, and pins absolutely positioned at `(* page-x zoom)`/`(* page-y zoom)`
  inside it (so the translate maps them to the right screen pixel).

  Interactions:
    • click a pin -> popover with body + author + Resolve/Delete + 'Send to
      <provider>' (posts to the configured webhook).
    • click the canvas background -> drops a new page-floating comment at
      the click's page coords (drafted into a small inline form).

  Reduced-motion safe: no transitions/animations. Pins appear/disappear
  instantly. Coral accent #f28b82, Lucide message-circle icon."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.workspace.team-sharing :as ts]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.util.i18n :refer [tr]]
   [clojure.string :as str]
   [rumext.v2 :as mf]))

;; Coral + grey accents (Ovion theme).
(def ^:private coral "#f28b82")
(def ^:private grey  "#7d7d7d")

(def ^:private pin-size 22)
(def ^:private pin-offset (/ pin-size 2))

;; --- Lucide icons (lucide.dev, MIT) — 24x24, stroke currentColor, sw 2 ---

(defn- icon-message-circle
  []
  [:svg {:xmlns "http://www.w3.org/2000/svg"
         :width 14 :height 14 :viewBox "0 0 24 24" :fill "none"
         :stroke "currentColor" :stroke-width 2 :stroke-linecap "round"
         :stroke-linejoin "round" :aria-hidden "true"}
   [:path {:d "M7.9 20A9 9 0 1 0 4 16.1L2 22Z"}]])

(defn- icon-check
  []
  [:svg {:xmlns "http://www.w3.org/2000/svg"
         :width 14 :height 14 :viewBox "0 0 24 24" :fill "none"
         :stroke "currentColor" :stroke-width 2 :stroke-linecap "round"
         :stroke-linejoin "round" :aria-hidden "true"}
   [:path {:d "M20 6 9 17l-5-5"}]])

(defn- icon-trash
  []
  [:svg {:xmlns "http://www.w3.org/2000/svg"
         :width 14 :height 14 :viewBox "0 0 24 24" :fill "none"
         :stroke "currentColor" :stroke-width 2 :stroke-linecap "round"
         :stroke-linejoin "round" :aria-hidden "true"}
   [:path {:d "M3 6h18"}]
   [:path {:d "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"}]
   [:line {:x1 10 :y1 11 :x2 10 :y2 17}]
   [:line {:x1 14 :y1 11 :x2 14 :y2 17}]])

(defn- icon-send
  []
  [:svg {:xmlns "http://www.w3.org/2000/svg"
         :width 14 :height 14 :viewBox "0 0 24 24" :fill "none"
         :stroke "currentColor" :stroke-width 2 :stroke-linecap "round"
         :stroke-linejoin "round" :aria-hidden "true"}
   [:path {:d "M14.536 21.086a.5.5 0 0 0 .937-.024l6.5-19a.496.496 0 0 0-.635-.635l-19 6.5a.5.5 0 0 0-.024.937l7.93 3.18a2 2 0 0 1 1.112 1.11z"}]
   [:path {:d "m21.086 9.536-9.536 9.536"}]])

;; --- Pin + popover ---------------------------------------------------------

(mf/defc comment-pin*
  {::mf/private true}
  [{:keys [comment shape-id zoom on-resolve on-delete on-send on-click open?]}]
  (let [x         (* (:x comment 0) zoom)
        y         (* (:y comment 0) zoom)
        resolved? (boolean (:resolved? comment))
        cid       (:id comment)]
    [:*
     [:button
      {:type "button"
       :title (:body comment)
       :aria-label (tr "workspace.comments.pin")
       :on-click on-click
       :style
       {:position "absolute"
        :left (dm/str (- x pin-offset) "px")
        :top  (dm/str (- y pin-offset) "px")
        :width (dm/str pin-size "px")
        :height (dm/str pin-size "px")
        :border "none"
        :border-radius "50%"
        :background (if resolved? "rgba(242,139,130,0.35)" coral)
        :color "#ffffff"
        :cursor "pointer"
        :display "flex"
        :align-items "center"
        :justify-content "center"
        :box-shadow (when (not resolved?) "0 1px 4px rgba(242,139,130,0.45)")
        :padding "0"
        :z-index 5}}
      (icon-message-circle)]

     (when open?
       [:div
        {:style
         {:position "absolute"
          :left (dm/str (- x pin-offset) "px")
          :top  (dm/str (+ y pin-offset 6) "px")
          :background "#ffffff"
          :border (dm/str "1px solid " coral)
          :border-radius "8px"
          :box-shadow "0 4px 14px rgba(0,0,0,0.16)"
          :padding "10px"
          :width "220px"
          :z-index 12
          :font-size "12px"
          :color "#1a1a1a"}}
        [:div {:style {:font-weight 600 :margin-bottom "4px"
                       :color "#1a1a1a"}}
         (:author comment (tr "workspace.comments.anonymous"))]
        [:div {:style {:white-space "pre-wrap" :word-break "break-word"
                       :color "#333333" :margin-bottom "8px"}}
         (:body comment "")]
        (when resolved?
          [:div {:style {:font-size "11px" :color coral :margin-bottom "6px"
                         :display "flex" :align-items "center" :gap "4px"}}
           (icon-check)
           [:span (tr "workspace.comments.resolved")]])
        [:div {:style {:display "flex" :gap "6px" :flex-wrap "wrap"}}
         [:button
          {:type "button"
           :on-click on-resolve
           :style {:display "inline-flex" :align-items "center" :gap "4px"
                   :height "24px" :padding "0 8px" :border-radius "5px"
                   :border (dm/str "1px solid rgba(242,139,130,0.4)")
                   :background "rgba(242,139,130,0.08)" :color coral
                   :cursor "pointer" :font-size "11px"}}
          (icon-check)
          [:span (if resolved?
                   (tr "workspace.comments.unresolve")
                   (tr "workspace.comments.resolve"))]]
         [:button
          {:type "button"
           :on-click on-delete
           :style {:display "inline-flex" :align-items "center" :gap "4px"
                   :height "24px" :padding "0 8px" :border-radius "5px"
                   :border "1px solid rgba(125,125,125,0.3)"
                   :background "transparent" :color grey
                   :cursor "pointer" :font-size "11px"}}
          (icon-trash)
          [:span (tr "workspace.comments.delete")]]
         [:button
          {:type "button"
           :on-click on-send
           :style {:display "inline-flex" :align-items "center" :gap "4px"
                   :height "24px" :padding "0 8px" :border-radius "5px"
                   :border (dm/str "1px solid rgba(242,139,130,0.4)")
                   :background coral :color "#ffffff"
                   :cursor "pointer" :font-size "11px"}}
          (icon-send)
          [:span (tr "workspace.comments.send")]]]])]))

;; --- Draft (click-canvas-to-drop-a-page-comment) ---------------------------

(mf/defc comment-draft*
  {::mf/private true}
  [{:keys [x y zoom author on-submit on-cancel]}]
  (let [body*  (mf/use-state "")
        sx     (* x zoom)
        sy     (* y zoom)
        on-body (mf/use-fn #(reset! body* (.. % -target -value)))
        on-submit-fn
        (mf/use-fn
         (mf/deps @body* author x y)
         (fn []
           (let [body (str/trim @body*)]
             (when (not (empty? body))
               (on-submit (ts/new-comment author body x y)))
             (on-cancel))))
        on-key
        (mf/use-fn
         (mf/deps on-submit-fn on-cancel)
         (fn [e]
           (let [key (.. e -key)]
             (cond
               (= key "Enter") (do (.preventDefault e) (on-submit-fn))
               (= key "Escape") (do (.preventDefault e) (on-cancel))))))]
    ;; Reduced-motion safe: no transition.
    [:div
     {:style
      {:position "absolute"
       :left (dm/str (- sx 60) "px")
       :top  (dm/str (+ sy pin-offset 6) "px")
       :background "#ffffff"
       :border (dm/str "1px solid " coral)
       :border-radius "8px"
       :box-shadow "0 4px 14px rgba(0,0,0,0.16)"
       :padding "10px"
       :width "200px"
       :z-index 13
       :font-size "12px"}}
     [:div {:style {:margin-bottom "6px" :color grey :font-size "11px"}}
      (tr "workspace.comments.new-comment")]
     [:textarea
      {:value @body*
       :placeholder (tr "workspace.comments.body-placeholder")
       :on-change on-body
       :on-key-down on-key
       :rows 3
       :style {:width "100%" :border (dm/str "1px solid " coral)
               :border-radius "5px" :padding "6px" :font-size "12px"
               :resize "vertical" :min-height "48px" :box-sizing "border-box"}}]
     [:div {:style {:display "flex" :gap "6px" :margin-top "6px"}}
      [:button
       {:type "button" :on-click on-submit-fn
        :style {:height "24px" :padding "0 10px" :border-radius "5px"
                :border "none" :background coral :color "#ffffff"
                :cursor "pointer" :font-size "11px"}}
       (tr "workspace.comments.add")]
      [:button
       {:type "button" :on-click on-cancel
        :style {:height "24px" :padding "0 10px" :border-radius "5px"
                :border "1px solid rgba(125,125,125,0.3)"
                :background "transparent" :color grey
                :cursor "pointer" :font-size "11px"}}
       (tr "workspace.comments.cancel")]]]))

;; --- Main overlay ----------------------------------------------------------

(mf/defc comments-overlay*
  "Canvas overlay rendering comment pins. Mounted in workspace.cljs ONLY when
  `ts/comments-mode-ref` is true — when off, workspace.cljs emits nothing,
  so this component is never mounted (byte-identical). Additional guard
  inside: derefs `comments-mode-ref` and returns nil if somehow false."
  [{:keys [vbox vport zoom page]}]
  (let [mode?       (mf/deref ts/comments-mode-ref)]
    (if (not mode?)
      nil
      (let [vbox-x    (dm/get-prop vbox :x)
            vbox-y    (dm/get-prop vbox :y)
            vport-w   (dm/get-prop vport :width)
            vport-h   (dm/get-prop vport :height)
            pos-x     (* (- vbox-x) zoom)
            pos-y     (* (- vbox-y) zoom)

            page-comments (ts/read-page-comments page)
            objects       (get page :objects)
            shape-comments
            (mf/with-memo [objects]
              (into []
                    (mapcat
                     (fn [[shape-id shape]]
                       (let [cs (ts/read-comments shape)]
                         (map #(hash-map :comment % :shape-id shape-id) cs))))
                    objects))

            all-comments
            (into []
                  (concat
                   (map #(hash-map :comment % :shape-id nil) page-comments)
                   shape-comments))

            open-id*  (mf/use-state nil)
            draft*    (mf/use-state nil)
            author*   (mf/use-state "")

            on-author (mf/use-fn #(reset! author* (.. % -target -value)))

            open-pin
            (mf/use-fn
             (fn [cid]
               (fn [_]
                 (reset! open-id* (if (= @open-id* cid) nil cid))
                 (reset! draft* nil))))

            on-resolve-shape
            (mf/use-fn
             (fn [shape-id cid]
               (fn [_] (st/emit! (ts/resolve-comment-event shape-id cid)))))

            on-resolve-page
            (mf/use-fn
             (fn [cid]
               (fn [_] (st/emit! (ts/resolve-page-comment-event cid)))))

            on-delete-shape
            (mf/use-fn
             (fn [shape-id cid]
               (fn [_]
                 (st/emit! (ts/delete-comment-event shape-id cid))
                 (reset! open-id* nil))))

            on-delete-page
            (mf/use-fn
             (fn [cid]
               (fn [_]
                 (st/emit! (ts/delete-page-comment-event cid))
                 (reset! open-id* nil))))

            on-send
            (mf/use-fn
             (fn [comment shape-id]
               (fn [_]
                 (let [c (assoc comment :shape-id shape-id)]
                   (-> (ts/post-comment-to-webhook c)
                       (.catch (fn [_err] nil)))))))

            on-canvas-click
            (mf/use-fn
             (fn [e]
               ;; Only the background click (not a pin click) should drop a
               ;; draft. Pins call preventDefault/stopPropagation in their
               ;; own handlers; here we check the target is the container.
               (let [target (.. e -target)
                     tag    (.. target -tagName)]
                 (when (or (= tag "DIV") (= tag "SECTION"))
                   (let [rect   (.. e -currentTarget getBoundingClientRect)
                         client-x (.. e -clientX)
                         client-y (.. e -clientY)
                         screen-x (- client-x (.-left rect))
                         screen-y (- client-y (.-top rect))
                         page-x  (+ (/ screen-x zoom) vbox-x)
                         page-y  (+ (/ screen-y zoom) vbox-y)]
                     (reset! draft* {:x page-x :y page-y})
                     (reset! open-id* nil))))))

            on-draft-submit
            (mf/use-fn
             (fn [comment]
               (st/emit! (ts/add-page-comment-event comment))
               (reset! draft* nil)))

            on-draft-cancel
            (mf/use-fn #(reset! draft* nil))]

        [:div
         {:class (stl/css :comments-overlay)
          :on-click on-canvas-click
          :style
          {:position "absolute"
           :left 0 :top 0
           :width (dm/str vport-w "px")
           :height (dm/str vport-h "px")
           :pointer-events "auto"
           :z-index 4
           ;; Reduced-motion safe: no transition.
           :cursor "crosshair"}}

         [:div
          {:style
           {:position "absolute"
            :left 0 :top 0
            :width (dm/str vport-w "px")
            :height (dm/str vport-h "px")
            :transform (dm/fmt "translate(%px, %px)" pos-x pos-y)
            :transform-origin "0 0"}}

          ;; Author input (small, top-left of overlay).
          [:div
           {:style
            {:position "absolute"
             :left (dm/str (- pos-x) "px")
             :top  (dm/str (- pos-y) "px")
             :display "flex" :align-items "center" :gap "6px"
             :background "rgba(255,255,255,0.9)"
             :border (dm/str "1px solid " coral)
             :border-radius "6px" :padding "4px 8px"
             :z-index 6}}
           [:span {:style {:font-size "11px" :color grey}}
            (tr "workspace.comments.author")]
           [:input
            {:type "text"
             :value @author*
             :placeholder (tr "workspace.comments.author-placeholder")
             :on-change on-author
             :style {:border "1px solid rgba(125,125,125,0.3)"
                     :border-radius "4px" :padding "2px 6px"
                     :font-size "11px" :width "120px"}}]]

          (for [{:keys [comment shape-id]} all-comments]
            (let [cid (:id comment)]
              ^{:key (str (or shape-id "page") "-" cid)}
              [:> comment-pin*
               {:comment comment
                :shape-id shape-id
                :zoom zoom
                :open? (= @open-id* cid)
                :on-click (open-pin cid)
                :on-resolve (if shape-id
                              (on-resolve-shape shape-id cid)
                              (on-resolve-page cid))
                :on-delete (if shape-id
                             (on-delete-shape shape-id cid)
                             (on-delete-page cid))
                :on-send (on-send comment shape-id)}]))

          (when-let [d @draft*]
            [:> comment-draft*
             {:x (:x d) :y (:y d) :zoom zoom
              :author (str/trim (or @author* ""))
              :on-submit on-draft-submit
              :on-cancel on-draft-cancel}])]]))))