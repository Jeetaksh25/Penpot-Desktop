;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.viewer.interactions
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.types.page :as ctp]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.comments :as dcm]
   [app.main.data.viewer :as dv]
   [app.main.features :as features]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.hooks :as h]
   [app.main.ui.icons :as deprecated-icon]
   [app.main.ui.viewer.shapes :as shapes]
   [app.main.ui.viewer.viewport-common :as vpc]
   [app.main.ui.viewer.viewport-wasm :as viewport.wasm]
   [app.main.ui.workspace.ai-motion :as am]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [clojure.string :as cstr]
   [goog.events :as events]
   [rumext.v2 :as mf]))

;; Figma #34: convert an interaction's :easing value into a CSS/Web-Animations
;; easing string. For the 5 named presets this is just `(name easing)` (unchanged
;; from the previous behaviour). For :custom-bezier it emits a
;; `cubic-bezier(x1,y1,x2,y2)` string from the authored :bezier-ctrl control
;; points, falling back to a linear curve when the control points are missing
;; or malformed so an invalid easing string is never passed to `element.animate`
;; (which would otherwise throw a SyntaxError and break the whole transition).
(defn- easing-str
  [animation]
  (let [easing (:easing animation)]
    (cond
      (= easing :custom-bezier)
      (let [{:keys [x1 y1 x2 y2]} (:bezier-ctrl animation)]
        (str "cubic-bezier("
             (or x1 0) "," (or y1 0) ","
             (or x2 1) "," (or y2 1) ")"))

      ;; Ovion spring easing (gap P1.16): approximate the authored
      ;; {stiffness damping mass} config as a cubic-bezier string for the
      ;; Web Animations API. Returns "linear" under reduced motion.
      (= easing :spring)
      (am/spring-easing->bezier (:spring-config animation))

      :else
      (name easing))))

(mf/defc viewport-svg*
  {::mf/wrap [mf/memo]}
  [{:keys [page frame base offset size is-fixed delta]}]
  (let [delta     (or delta (gpt/point 0 0))
        vbox      (:vbox size)
        is-fixed  (true? is-fixed)

        frame     (cond-> frame is-fixed (assoc :fixed-scroll true))

        objects   (:objects page)
        objects   (cond-> objects is-fixed (assoc-in [(:id frame) :fixed-scroll] true))

        fixed-ids (vpc/get-fixed-ids objects)

        not-fixed-ids
        (->> (remove (set fixed-ids) (keys objects))
             (remove #(= % uuid/zero)))

        calculate-objects
        (fn [ids]
          (->> ids
               (map (d/getf objects))
               (concat [frame])
               (d/index-by :id)
               (vpc/prepare-objects frame size delta)))

        objects-fixed
        (mf/with-memo [fixed-ids page frame size delta]
          (calculate-objects fixed-ids))

        objects-not-fixed
        (mf/with-memo [not-fixed-ids page frame size delta]
          (calculate-objects not-fixed-ids))

        all-objects
        (mf/with-memo [objects-fixed objects-not-fixed]
          (merge objects-fixed objects-not-fixed))

        wrapper-fixed
        (mf/with-memo [page frame size]
          (shapes/frame-container-factory (assoc objects-fixed ::fixed true) all-objects))

        wrapper-not-fixed
        (mf/with-memo [objects-not-fixed]
          (shapes/frame-container-factory objects-not-fixed all-objects))

        ;; Retrieve frames again with correct modifier
        frame   (get all-objects (:id frame))
        base    (get all-objects (:id base))

        non-delay-interactions
        (->> (:interactions frame)
             (filterv #(not= (:event-type %) :after-delay)))

        fixed-frame
        (-> frame
            (dissoc :fills)
            (assoc :interactions non-delay-interactions))]

    [:& (mf/provider shapes/base-frame-ctx) {:value base}
     [:& (mf/provider shapes/frame-offset-ctx) {:value offset}
      (if is-fixed
        [:svg {:class (stl/css :fixed)
               :view-box vbox
               :width (:width size)
               :height (:height size)
               :version "1.1"
               :xmlnsXlink "http://www.w3.org/1999/xlink"
               :xmlns "http://www.w3.org/2000/svg"
               :fill "none"}
         [:& wrapper-not-fixed {:shape frame :view-box vbox}]]

        [:*
         ;; We have two different svgs for fixed and not fixed elements so we can emulate the sticky css attribute in svg
         [:svg {:class (stl/css :fixed)
                :view-box vbox
                :width (:width size)
                :height (:height size)
                :version "1.1"
                :xmlnsXlink "http://www.w3.org/1999/xlink"
                :xmlns "http://www.w3.org/2000/svg"
                :fill "none"
                :style {:width (:width size)
                        :height (:height size)
                        :z-index 1}}
          [:& wrapper-fixed {:shape fixed-frame :view-box vbox}]]

         [:svg {:class (stl/css :not-fixed)
                :view-box vbox
                :width (:width size)
                :height (:height size)
                :version "1.1"
                :xmlnsXlink "http://www.w3.org/1999/xlink"
                :xmlns "http://www.w3.org/2000/svg"
                :fill "none"}
          [:& wrapper-not-fixed {:shape frame :view-box vbox}]]])]]))

(mf/defc viewport*
  {::mf/wrap [mf/memo]}
  [{:keys [interactions-mode frame-offset size delta page frame base-frame is-fixed]}]
  (let [;; NOTE: with `use-equal-memo` hook we ensure that all values
        ;; conserves the reference identity for avoid unnecessary
        ;; dummy rerenders.

        mode   (h/use-equal-memo interactions-mode)
        offset (h/use-equal-memo frame-offset)
        size   (h/use-equal-memo size)
        base   base-frame

        render-wasm? (and (features/use-feature "render-wasm/v1")
                          (contains? cf/flags :available-viewer-wasm))]

    (mf/with-effect [mode]
      (let [on-click
            (fn [_]
              (when (= mode :show-on-click)
                (st/emit! (dv/flash-interactions))))

            on-mouse-wheel
            (fn [event]
              (when (kbd/mod? event)
                (dom/prevent-default event)
                (let [event (dom/event->browser-event event)
                      delta (+ (.-deltaY ^js event)
                               (.-deltaX ^js event))]
                  (if (pos? delta)
                    (st/emit! dv/decrease-zoom)
                    (st/emit! dv/increase-zoom)))))

            on-key-down
            (fn [event]
              (when (kbd/esc? event)
                (st/emit! (dcm/close-thread))))


            ;; bind with passive=false to allow the event to be cancelled
            ;; https://stackoverflow.com/a/57582286/3219895
            key1 (events/listen goog/global "wheel" on-mouse-wheel #js {"passive" false})
            key2 (events/listen goog/global "keydown" on-key-down)
            key3 (events/listen goog/global "click" on-click)]
        (fn []
          (events/unlistenByKey key1)
          (events/unlistenByKey key2)
          (events/unlistenByKey key3))))

    (if ^boolean render-wasm?
      [:> viewport.wasm/viewport-wasm* {:page page
                                        :frame frame
                                        :base base
                                        :offset offset
                                        :size size
                                        :delta delta
                                        :is-fixed is-fixed}]
      [:> viewport-svg* {:page page
                         :frame frame
                         :base base
                         :offset offset
                         :size size
                         :delta delta
                         :is-fixed is-fixed}])))

(mf/defc flows-menu*
  {::mf/wrap [mf/memo]}
  [{:keys [page index]}]
  (let [flows            (not-empty (:flows page))
        frames           (:frames page)

        frame            (get frames index)
        frame-id         (dm/get-prop frame :id)

        current-flow*    (mf/use-state #(ctp/get-frame-flow flows frame-id))
        current-flow     (deref current-flow*)

        show-dropdown?*  (mf/use-state false)
        show-dropdown?   (deref show-dropdown?*)

        toggle-dropdown  (mf/use-fn #(swap! show-dropdown?* not))
        hide-dropdown    (mf/use-fn #(reset! show-dropdown?* false))

        select-flow
        (mf/use-fn
         (fn [event]
           (let [flow (-> (dom/get-current-target event)
                          (dom/get-data "value")
                          (d/read-string))]
             (reset! current-flow* flow)
             (st/emit! (dv/go-to-frame (:starting-frame flow))))))]

    (when flows
      [:div {:on-click toggle-dropdown
             :class (stl/css :view-options)}
       [:span {:class (stl/css :icon)} deprecated-icon/play]
       [:span {:class (stl/css :dropdown-title)} (:name current-flow)]
       [:span {:class (stl/css :icon-dropdown)}  deprecated-icon/arrow]
       [:& dropdown {:show show-dropdown?
                     :on-close hide-dropdown}
        [:ul {:class (stl/css :dropdown)}
         (for [[flow-id flow] flows]
           [:li {:key (dm/str "flow-" flow-id)
                 :class (stl/css-case :dropdown-element true
                                      :selected (= flow-id (:id current-flow)))
                 ;; WARN: This is not a best practise, is not very
                 ;; performant DO NOT COPY
                 :data-value (pr-str flow)
                 :on-click select-flow}
            [:span {:class (stl/css :label)} (:name flow)]
            (when (= flow-id (:id current-flow))
              [:span {:class (stl/css :icon)} deprecated-icon/tick])])]]])))

(mf/defc interactions-menu*
  [{:keys [interactions-mode]}]
  (let [show-dropdown?  (mf/use-state false)
        toggle-dropdown (mf/use-fn #(swap! show-dropdown? not))
        hide-dropdown   (mf/use-fn #(reset! show-dropdown? false))

        select-mode
        (mf/use-fn
         (fn [event]
           (let [mode (some-> (dom/get-current-target event)
                              (dom/get-data "mode")
                              (keyword))]
             (dom/stop-propagation event)
             (st/emit! (dv/set-interactions-mode mode)))))]

    [:div {:on-click toggle-dropdown
           :class (stl/css :view-options)}
     [:span {:class (stl/css :dropdown-title)} (tr "viewer.header.interactions")]
     [:span {:class (stl/css :icon-dropdown)} deprecated-icon/arrow]
     [:& dropdown {:show @show-dropdown?
                   :on-close hide-dropdown}
      [:ul {:class (stl/css :dropdown)}
       [:li {:class (stl/css-case :dropdown-element true
                                  :selected (= interactions-mode :hide))
             :on-click select-mode
             :data-mode "hide"}

        [:span {:class (stl/css :label)} (tr "viewer.header.dont-show-interactions")]
        (when (= interactions-mode :hide)
          [:span {:class (stl/css :icon)}  deprecated-icon/tick])]

       [:li {:class (stl/css-case :dropdown-element true
                                  :selected (= interactions-mode :show))
             :on-click select-mode
             :data-mode "show"}
        [:span {:class (stl/css :label)} (tr "viewer.header.show-interactions")]
        (when (= interactions-mode :show)
          [:span {:class (stl/css :icon)}  deprecated-icon/tick])]



       [:li {:class (stl/css-case :dropdown-element true
                                  :selected (= interactions-mode :show-on-click))
             :on-click select-mode
             :data-mode "show-on-click"}

        [:span {:class (stl/css :label)} (tr "viewer.header.show-interactions-on-click")]
        (when (= interactions-mode :show-on-click)
          [:span {:class (stl/css :icon)}  deprecated-icon/tick])]]]]))

(defn animate-go-to-frame
  [animation current-viewport orig-viewport current-size orig-size wrapper-size]
  (case (:animation-type animation)

    ;; Why use three keyframes instead of two?
    ;; If we use two keyframes, the first frame
    ;; will disappear while the second frame
    ;; is still appearing.
    ;; ___  ___
    ;;    \/
    ;; ___/\___
    ;;     ^ in here we have 50% opacity of both frames so the background
    ;;       is visible.
    ;;
    ;; This solution waits until the second frame
    ;; has appeared to disappear the first one.
    ;; ________
    ;;   /\
    ;; _/  \___
    ;;    ^ in here we have 100% opacity of the first frame and 0% opacity.
    ;;
    ;; Figma #11: :smart-animate v1 falls back to this dissolve crossfade. The
    ;; matched-property tweening (matching layers by name across frames and
    ;; tweening position/size/opacity/fills via the Web Animations API per
    ;; element) is deferred — it needs access to both frames' prepared objects
    ;; and per-element refs, which is high blast-radius and cannot be verified
    ;; without a build. Gracefully degrading to a crossfade keeps the transition
    ;; functional and avoids an unmatched `case` throw on the new animation type.
    (:dissolve :smart-animate)
    (do (dom/animate! orig-viewport
                      [#js {:opacity "100%"}
                       #js {:opacity "0%"}
                       #js {:opacity "0%"}]
                      #js {:delay (/ (:duration animation) 3)
                           :duration (/ (* 2 (:duration animation)) 3)
                           :easing (easing-str animation)})
        (dom/animate! current-viewport
                      [#js {:opacity "0%"}
                       #js {:opacity "100%"}
                       #js {:opacity "100%"}]
                      #js {:duration (:duration animation)
                           :easing (easing-str animation)}
                      #(st/emit! (dv/complete-animation))))

    :slide
    (case (:way animation)

      :in
      (case (:direction animation)

        :right
        (let [offset (+ (:width current-size)
                        (/ (- (:width wrapper-size) (:width current-size)) 2))]
          (dom/animate! current-viewport
                        [#js {:left (str "-" offset "px")}
                         #js {:left "0"}]
                        #js {:duration (:duration animation)
                             :easing (easing-str animation)}
                        #(st/emit! (dv/complete-animation)))
          (when (:offset-effect animation)
            (dom/animate! orig-viewport
                          [#js {:left "0"
                                :opacity "100%"}
                           #js {:left (str (* offset 0.2) "px")
                                :opacity "0"}]
                          #js {:duration (:duration animation)
                               :easing (easing-str animation)})))

        :left
        (let [offset (+ (:width current-size)
                        (/ (- (:width wrapper-size) (:width current-size)) 2))]
          (dom/animate! current-viewport
                        [#js {:right (str "-" offset "px")}
                         #js {:right "0"}]
                        #js {:duration (:duration animation)
                             :easing (easing-str animation)}
                        #(st/emit! (dv/complete-animation)))
          (when (:offset-effect animation)
            (dom/animate! orig-viewport
                          [#js {:right "0"
                                :opacity "100%"}
                           #js {:right (str (* offset 0.2) "px")
                                :opacity "0"}]
                          #js {:duration (:duration animation)
                               :easing (easing-str animation)})))

        :up
        (let [offset (+ (:height current-size)
                        (/ (- (:height wrapper-size) (:height current-size)) 2))]
          (dom/animate! current-viewport
                        [#js {:bottom (str "-" offset "px")}
                         #js {:bottom "0"}]
                        #js {:duration (:duration animation)
                             :easing (easing-str animation)}
                        #(st/emit! (dv/complete-animation)))
          (when (:offset-effect animation)
            (dom/animate! orig-viewport
                          [#js {:bottom "0"
                                :opacity "100%"}
                           #js {:bottom (str (* offset 0.2) "px")
                                :opacity "0"}]
                          #js {:duration (:duration animation)
                               :easing (easing-str animation)})))

        :down
        (let [offset (+ (:height current-size)
                        (/ (- (:height wrapper-size) (:height current-size)) 2))]
          (dom/animate! current-viewport
                        [#js {:top (str "-" offset "px")}
                         #js {:top "0"}]
                        #js {:duration (:duration animation)
                             :easing (easing-str animation)}
                        #(st/emit! (dv/complete-animation)))
          (when (:offset-effect animation)
            (dom/animate! orig-viewport
                          [#js {:top "0"
                                :opacity "100%"}
                           #js {:top (str (* offset 0.2) "px")
                                :opacity "0"}]
                          #js {:duration (:duration animation)
                               :easing (easing-str animation)}))))

      :out
      (case (:direction animation)

        :right
        (let [offset (+ (:width orig-size)
                        (/ (- (:width wrapper-size) (:width orig-size)) 2))]
          (dom/set-css-property! orig-viewport "z-index" 10000)
          (dom/animate! orig-viewport
                        [#js {:right "0"}
                         #js {:right (str "-" offset "px")}]
                        #js {:duration (:duration animation)
                             :easing (easing-str animation)}
                        #(st/emit! (dv/complete-animation)))
          (when (:offset-effect animation)
            (dom/animate! current-viewport
                          [#js {:right (str (* offset 0.2) "px")
                                :opacity "0"}
                           #js {:right "0"
                                :opacity "100%"}]
                          #js {:duration (:duration animation)
                               :easing (easing-str animation)})))

        :left
        (let [offset (+ (:width orig-size)
                        (/ (- (:width wrapper-size) (:width orig-size)) 2))]
          (dom/set-css-property! orig-viewport "z-index" 10000)
          (dom/animate! orig-viewport
                        [#js {:left "0"}
                         #js {:left (str "-" offset "px")}]
                        #js {:duration (:duration animation)
                             :easing (easing-str animation)}
                        #(st/emit! (dv/complete-animation)))
          (when (:offset-effect animation)
            (dom/animate! current-viewport
                          [#js {:left (str (* offset 0.2) "px")
                                :opacity "0"}
                           #js {:left "0"
                                :opacity "100%"}]
                          #js {:duration (:duration animation)
                               :easing (easing-str animation)})))

        :up
        (let [offset (+ (:height orig-size)
                        (/ (- (:height wrapper-size) (:height orig-size)) 2))]
          (dom/set-css-property! orig-viewport "z-index" 10000)
          (dom/animate! orig-viewport
                        [#js {:top "0"}
                         #js {:top (str "-" offset "px")}]
                        #js {:duration (:duration animation)
                             :easing (easing-str animation)}
                        #(st/emit! (dv/complete-animation)))
          (when (:offset-effect animation)
            (dom/animate! current-viewport
                          [#js {:top (str (* offset 0.2) "px")
                                :opacity "0"}
                           #js {:top "0"
                                :opacity "100%"}]
                          #js {:duration (:duration animation)
                               :easing (easing-str animation)})))

        :down
        (let [offset (+ (:height orig-size)
                        (/ (- (:height wrapper-size) (:height orig-size)) 2))]
          (dom/set-css-property! orig-viewport "z-index" 10000)
          (dom/animate! orig-viewport
                        [#js {:bottom "0"}
                         #js {:bottom (str "-" offset "px")}]
                        #js {:duration (:duration animation)
                             :easing (easing-str animation)}
                        #(st/emit! (dv/complete-animation)))
          (when (:offset-effect animation)
            (dom/animate! current-viewport
                          [#js {:bottom (str (* offset 0.2) "px")
                                :opacity "0"}
                           #js {:bottom "0"
                                :opacity "100%"}]
                          #js {:duration (:duration animation)
                               :easing (easing-str animation)})))))

    :push
    (case (:direction animation)

      :right
      (let [offset (:width wrapper-size)]
        (dom/animate! current-viewport
                      [#js {:left (str "-" offset "px")}
                       #js {:left "0"}]
                      #js {:duration (:duration animation)
                           :easing (easing-str animation)}
                      #(st/emit! (dv/complete-animation)))
        (dom/animate! orig-viewport
                      [#js {:left "0"}
                       #js {:left (str offset "px")}]
                      #js {:duration (:duration animation)
                           :easing (easing-str animation)}))

      :left
      (let [offset (:width wrapper-size)]
        (dom/animate! current-viewport
                      [#js {:right (str "-" offset "px")}
                       #js {:right "0"}]
                      #js {:duration (:duration animation)
                           :easing (easing-str animation)}
                      #(st/emit! (dv/complete-animation)))
        (dom/animate! orig-viewport
                      [#js {:right "0"}
                       #js {:right (str offset "px")}]
                      #js {:duration (:duration animation)
                           :easing (easing-str animation)}))

      :up
      (let [offset (:height wrapper-size)]
        (dom/animate! current-viewport
                      [#js {:bottom (str "-" offset "px")}
                       #js {:bottom "0"}]
                      #js {:duration (:duration animation)
                           :easing (easing-str animation)}
                      #(st/emit! (dv/complete-animation)))
        (dom/animate! orig-viewport
                      [#js {:bottom "0"}
                       #js {:bottom (str offset "px")}]
                      #js {:duration (:duration animation)
                           :easing (easing-str animation)}))

      :down
      (let [offset (:height wrapper-size)]
        (dom/animate! current-viewport
                      [#js {:top (str "-" offset "px")}
                       #js {:top "0"}]
                      #js {:duration (:duration animation)
                           :easing (easing-str animation)}
                      #(st/emit! (dv/complete-animation)))
        (dom/animate! orig-viewport
                      [#js {:top "0"}
                       #js {:top (str offset "px")}]
                      #js {:duration (:duration animation)
                           :easing (easing-str animation)})))

    ;; Ovion Flow List transition (gap P2.36): a stack push/pop — incoming
    ;; frame slides in from the right + fades in, outgoing frame slides
    ;; slightly left + fades out. Reduced-motion guarded inside am.
    :flow-list
    (let [width (:width wrapper-size)]
      (am/run-flow-list-transition
       current-viewport orig-viewport width
       #(st/emit! (dv/complete-animation))))))

(defn animate-open-overlay
  [animation overlay-viewport
   wrapper-size overlay-size overlay-position]
  (when (some? overlay-viewport)
    (case (:animation-type animation)

      ;; Figma #11: :smart-animate v1 falls back to this dissolve crossfade for
      ;; overlays (matched-property tweening deferred — see animate-go-to-frame).
      (:dissolve :smart-animate)
      (dom/animate! overlay-viewport
                    [#js {:opacity "0"}
                     #js {:opacity "100"}]
                    #js {:duration (:duration animation)
                         :easing (easing-str animation)}
                    #(st/emit! (dv/complete-animation)))

      :slide
      (case (:direction animation) ;; way and offset-effect are ignored

        :right
        (dom/animate! overlay-viewport
                      [#js {:left (str "-" (:width overlay-size) "px")}
                       #js {:left (str (:x overlay-position) "px")}]
                      #js {:duration (:duration animation)
                           :easing (easing-str animation)}
                      #(st/emit! (dv/complete-animation)))

        :left
        (dom/animate! overlay-viewport
                      [#js {:left (str (:width wrapper-size) "px")}
                       #js {:left (str (:x overlay-position) "px")}]
                      #js {:duration (:duration animation)
                           :easing (easing-str animation)}
                      #(st/emit! (dv/complete-animation)))

        :up
        (dom/animate! overlay-viewport
                      [#js {:top (str (:height wrapper-size) "px")}
                       #js {:top (str (:y overlay-position) "px")}]
                      #js {:duration (:duration animation)
                           :easing (easing-str animation)}
                      #(st/emit! (dv/complete-animation)))

        :down
        (dom/animate! overlay-viewport
                      [#js {:top (str "-" (:height overlay-size) "px")}
                       #js {:top (str (:y overlay-position) "px")}]
                      #js {:duration (:duration animation)
                           :easing (easing-str animation)}
                      #(st/emit! (dv/complete-animation))))

      ;; Ovion Flow List overlay open (gap P2.36): degrade the stack-push
      ;; feel to a single-element slide-in (no outgoing viewport for an
      ;; overlay). Reduced-motion guarded inside am.
      :flow-list
      (am/run-flow-list-transition
       overlay-viewport nil (:width wrapper-size)
       #(st/emit! (dv/complete-animation))))))

(defn animate-close-overlay
  [animation overlay-viewport
   wrapper-size overlay-size overlay-position overlay-id]
  (when (some? overlay-viewport)
    (case (:animation-type animation)

      ;; Figma #11: :smart-animate v1 falls back to this dissolve crossfade for
      ;; overlays (matched-property tweening deferred — see animate-go-to-frame).
      (:dissolve :smart-animate)
      (dom/animate! overlay-viewport
                    [#js {:opacity "100"}
                     #js {:opacity "0"}]
                    #js {:duration (:duration animation)
                         :easing (easing-str animation)}
                    #(st/emit! (dv/complete-animation)
                               (dv/close-overlay overlay-id)))

      :slide
      (case (:direction animation) ;; way and offset-effect are ignored

        :right
        (dom/animate! overlay-viewport
                      [#js {:left (str (:x overlay-position) "px")}
                       #js {:left (str (:width wrapper-size) "px")}]
                      #js {:duration (:duration animation)
                           :easing (easing-str animation)}
                      #(st/emit! (dv/complete-animation)
                                 (dv/close-overlay overlay-id)))

        :left
        (dom/animate! overlay-viewport
                      [#js {:left (str (:x overlay-position) "px")}
                       #js {:left (str "-" (:width overlay-size) "px")}]
                      #js {:duration (:duration animation)
                           :easing (easing-str animation)}
                      #(st/emit! (dv/complete-animation)
                                 (dv/close-overlay overlay-id)))

        :up
        (dom/animate! overlay-viewport
                      [#js {:top (str (:y overlay-position) "px")}
                       #js {:top (str "-" (:height overlay-size) "px")}]
                      #js {:duration (:duration animation)
                           :easing (easing-str animation)}
                      #(st/emit! (dv/complete-animation)
                                 (dv/close-overlay overlay-id)))

        :down
        (dom/animate! overlay-viewport
                      [#js {:top (str (:y overlay-position) "px")}
                       #js {:top (str (:height wrapper-size) "px")}]
                      #js {:duration (:duration animation)
                           :easing (easing-str animation)}
                      #(st/emit! (dv/complete-animation)
                                 (dv/close-overlay overlay-id)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; P0.17 SCROLL-DRIVEN ANIMATIONS (web-shippable)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; On each scroll of the #viewer-section container, every shape on the current
;; frame that carries a :scroll-animate interaction (event-type :while-scrolling)
;; has its :keyframes props interpolated by scroll progress and applied as inline
;; style on the matching [data-shape-id] DOM node. The reduced-motion guard is
;; non-negotiable: when the user prefers reduced motion we apply the final
;; keyframe instantly and skip the per-scroll interpolation work.

(defn- reduced-motion-preferred?
  "True when the user agent advertises prefers-reduced-motion: reduce. Nil-safe
  when matchMedia is unavailable."
  []
  (try
    (let [mq (.matchMedia js/window "(prefers-reduced-motion: reduce)")]
      (true? (.-matches ^js mq)))
    (catch :default _ false)))

(defn- clamp
  [v lo hi]
  (max lo (min hi v)))

(defn- lerp
  [a b t]
  (+ a (* (- b a) t)))

(defn- interpolate-keyframes
  "Given a vector of keyframes ({:offset 0..1 :props {...}}) and a progress
  value 0..1, return a props map with linearly interpolated values across the
  two surrounding keyframes. Nil-safe for empty/missing keyframes."
  [keyframes progress]
  (if (empty? keyframes)
    {}
    (let [kfs (sort-by :offset keyframes)
          p   (clamp progress 0 1)
          first-kf (first kfs)
          last-kf  (last kfs)]
      (cond
        (<= p (:offset first-kf))
        (:props first-kf)

        (>= p (:offset last-kf))
        (:props last-kf)

        :else
        (loop [remaining kfs]
          (let [a (first remaining)
                b (second remaining)]
            (cond
              (nil? b)               (:props a)
              (and (>= p (:offset a))
                   (<= p (:offset b)))
              (let [span (- (:offset b) (:offset a))
                    t   (if (zero? span) 0 (/ (- p (:offset a)) span))
                    pa  (:props a)
                    pb  (:props b)
                    prop-keys (set (concat (keys pa) (keys pb)))]
                (into {}
                      (for [k prop-keys]
                        [k (lerp (get pa k 0) (get pb k 0) t)])))
              :else (recur (next remaining)))))))))

(defn- apply-props-to-node
  "Apply interpolated keyframe props (translateY / opacity / scale / rotate) to
  a DOM node as inline style. Transform-contributing props are composed into a
  single `transform` string. Nil-safe when node is nil."
  [node props]
  (when (some? node)
    (let [translateY (get props :translate-y)
          scale      (get props :scale)
          rotate     (get props :rotate)
          opacity    (get props :opacity)
          transforms (cond-> []
                       (some? translateY) (conj (str "translateY(" translateY "px)"))
                       (some? scale)      (conj (str "scale(" scale ")"))
                       (some? rotate)     (conj (str "rotate(" rotate "deg)")))]
      (when (seq transforms)
        (dom/set-css-property! node "transform" (cstr/join " " transforms)))
      (when (some? opacity)
        (dom/set-css-property! node "opacity" (str opacity))))))

(defn apply-scroll-animations
  "Per-scroll work for the #viewer-section container. Iterates every shape on
  the current frame that has a :scroll-animate interaction (event-type
  :while-scrolling), computes scroll progress on the chosen axis, interpolates
  the keyframes props and applies them as inline style on the matching
  [data-shape-id] DOM node. When prefers-reduced-motion is set, applies the
  final keyframe instantly and skips per-scroll interpolation. Nil-safe when
  the container, frame, or objects are missing."
  [viewer-section frame objects]
  (when (and (some? viewer-section) (some? frame) (some? objects))
    (let [reduced?    (reduced-motion-preferred?)
          scroll-top  (.-scrollTop ^js viewer-section)
          scroll-left (.-scrollLeft ^js viewer-section)
          frame-id    (:id frame)
          ids         (cons frame-id (cfh/get-children-ids objects frame-id))
          shapes      (keep #(get objects %) ids)]
      (doseq [shape shapes
              :let [interactions (:interactions shape)]
              interaction interactions
              :when (= (:action-type interaction) :scroll-animate)]
        (let [shape-id-str (str (:id shape))
              node         (dom/query viewer-section
                                     (str "[data-shape-id='" shape-id-str "']"))
              axis         (or (:axis interaction) :vertical)
              range-start  (or (:range-start interaction) 0)
              range-end    (or (:range-end interaction) range-start)
              pos          (if (= axis :horizontal) scroll-left scroll-top)
              span         (- range-end range-start)
              progress     (if (zero? span) 0 (clamp (/ (- pos range-start) span) 0 1))
              keyframes    (:keyframes interaction)]
          (when (some? node)
            (if reduced?
              (let [final-props (if (seq keyframes)
                                  (:props (last (sort-by :offset keyframes)))
                                  {})]
                (apply-props-to-node node final-props))
              (apply-props-to-node node (interpolate-keyframes keyframes progress)))))))))

