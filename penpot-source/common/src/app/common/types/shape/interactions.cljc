;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.types.shape.interactions
  (:require
   [app.common.data :as d]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.bounds :as gsb]
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]))

;; WARNING: options are not deleted when changing event or action
;; type, so it can be restored if the user changes it back later.
;;
;; But that means that an interaction may have for example a delay or
;; destination, even if its type does not require it (but a previous
;; type did).
;;
;; So make sure to use has-delay/has-destination... functions, or
;; similar, before reading them.

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def event-types
  #{:click
    :mouse-press
    :mouse-over
    :mouse-enter
    :mouse-leave
    :after-delay
    ;; Figma #33: keyboard + input-change triggers.
    :key-down
    :on-change})

(def action-types
  #{:navigate
    :open-overlay
    :toggle-overlay
    :close-overlay
    :prev-screen
    :open-url
    ;; Figma #10: change-to variant action (interactive components).
    :change-to
    ;; Figma #73: swap one overlay for another; scroll to an object.
    :swap-overlay
    :scroll-to})

(def overlay-positioning-types
  #{:manual
    :center
    :top-left
    :top-right
    :top-center
    :bottom-left
    :bottom-right
    :bottom-center})

(def easing-types
  #{:linear
    :ease
    :ease-in
    :ease-out
    :ease-in-out
    ;; Figma #34: custom cubic-bezier easing (4 control points).
    :custom-bezier})

(def direction-types
  #{:right
    :left
    :up
    :down})

(def way-types
  #{:in :out})

(def animation-types
  #{:dissolve :slide :push
    ;; Figma #11: smart animate (matched-property tweening).
    :smart-animate})

;; Figma #34: the 4 control points of a custom cubic-bezier easing.
;; Stored alongside :easing in the animation map when :easing is :custom-bezier.
;; All four are optional so a partially-authored map (one coordinate set at a
;; time in the UI) still validates — `easing-str` falls back to 0/1 for any
;; missing coordinate, so a partial map never produces an invalid easing string.
(def schema:bezier-ctrl
  [:map {:title "BezierCtrl"}
   [:x1 {:optional true} ::sm/safe-number]
   [:y1 {:optional true} ::sm/safe-number]
   [:x2 {:optional true} ::sm/safe-number]
   [:y2 {:optional true} ::sm/safe-number]])

(def schema:dissolve-animation
  [:map {:title "AnimationDisolve"}
   [:animation-type [:= :dissolve]]
   [:duration ::sm/safe-int]
   [:easing [::sm/one-of easing-types]]
   [:way {:optional true} [::sm/one-of way-types]]
   [:offset-effect {:optional true} :boolean]
   [:direction {:optional true} [::sm/one-of direction-types]]
   ;; Figma #34: cubic-bezier control points (only meaningful when :easing is :custom-bezier).
   [:bezier-ctrl {:optional true} schema:bezier-ctrl]])

(def schema:slide-animation
  [:map {:title "AnimationSlide"}
   [:animation-type [:= :slide]]
   [:duration ::sm/safe-int]
   [:easing [::sm/one-of easing-types]]
   [:way [::sm/one-of way-types]]
   [:direction [::sm/one-of direction-types]]
   [:offset-effect :boolean]
   ;; Figma #34: cubic-bezier control points (only meaningful when :easing is :custom-bezier).
   [:bezier-ctrl {:optional true} schema:bezier-ctrl]])

(def schema:push-animation
  [:map {:title "PushAnimation"}
   [:animation-type [:= :push]]
   [:duration ::sm/safe-int]
   [:easing [::sm/one-of easing-types]]
   [:direction [::sm/one-of direction-types]]
   ;; Figma #34: cubic-bezier control points (only meaningful when :easing is :custom-bezier).
   [:bezier-ctrl {:optional true} schema:bezier-ctrl]])

;; Figma #11: smart animate. v1 schema only — the matched-property tweening
;; runtime is deferred (see viewer/interactions.cljs `animate-go-to-frame`,
;; where :smart-animate currently falls back to the dissolve crossfade so the
;; transition still happens without breaking). Duration + easing are authored
;; here; the renderer is responsible for the per-layer matching when wired.
(def schema:smart-animate-animation
  [:map {:title "SmartAnimateAnimation"}
   [:animation-type [:= :smart-animate]]
   [:duration ::sm/safe-int]
   [:easing [::sm/one-of easing-types]]
   [:bezier-ctrl {:optional true} schema:bezier-ctrl]])

(def schema:animation
  [:multi {:dispatch :animation-type
           :title "Animation"
           :gen/gen (sg/one-of (sg/generator schema:dissolve-animation)
                               (sg/generator schema:slide-animation)
                               (sg/generator schema:push-animation)
                               (sg/generator schema:smart-animate-animation))
           :decode/json #(update % :animation-type keyword)}
   [:dissolve schema:dissolve-animation]
   [:slide schema:slide-animation]
   [:push schema:push-animation]
   [:smart-animate schema:smart-animate-animation]])

(sm/register! ::animation schema:animation)

(def check-animation!
  (sm/check-fn schema:animation))

(def schema:generic-interaction-attrs
  [:map {:title "GenericInteractionAttrs"}
   [:action-type {:optional true} [::sm/one-of action-types]]
   [:event-type {:optional true} [::sm/one-of event-types]]
   [:destination {:optional true} [:maybe ::sm/uuid]]
   [:preserve-scroll {:optional true} :boolean]
   [:animation {:optional true} schema:animation]
   [:overlay-position {:optional true} ::gpt/point]
   [:overlay-pos-type {:optional true} [::sm/one-of overlay-positioning-types]]
   [:close-click-outside {:optional true} :boolean]
   [:background-overlay {:optional true} :boolean]
   [:position-relative-to {:optional true} [:maybe ::sm/uuid]]
   [:url {:optional true} :string]
   ;; Figma #73: per-interaction enable/disable without deleting. Absent = enabled.
   [:disabled {:optional true} :boolean]
   ;; Figma #33: key-down trigger filter. :key-code is a key name like "Enter",
   ;; "Escape", "a" (matching `KeyboardEvent.key`); absent = any key fires.
   [:key-code {:optional true} [:maybe :string]]
   [:key-modifiers {:optional true} [:maybe [:set :keyword]]]
   ;; Figma #10: change-to variant action. The component-instance target whose
   ;; variant should change, plus the property-name -> value overrides to apply.
   [:change-to-variant-id {:optional true} [:maybe ::sm/uuid]]
   [:change-to-props {:optional true} [:map-of :string :any]]
   ;; Figma #73: scroll-to target shape id (any object within a top-level frame).
   ;; Reuses :destination when the target is a frame; this field allows a
   ;; non-frame shape target that does not fit :destination's frame-oriented semantics.
   [:scroll-to-target {:optional true} [:maybe ::sm/uuid]]])

(def schema:navigate-interaction
  [:map {:title "NavigateInteraction"}
   [:action-type [:= :navigate]]
   [:event-type [::sm/one-of event-types]]
   [:destination {:optional true} [:maybe ::sm/uuid]]
   [:preserve-scroll {:optional true} :boolean]
   [:animation {:optional true} schema:animation]])

(def schema:open-overlay-interaction
  [:map {:title "OpenOverlayInteraction"}
   [:action-type [:= :open-overlay]]
   [:event-type [::sm/one-of event-types]]
   [:overlay-position {:optional true} ::gpt/point]
   [:overlay-pos-type {:optional true} [::sm/one-of overlay-positioning-types]]
   [:destination {:optional true} [:maybe ::sm/uuid]]
   [:close-click-outside {:optional true} :boolean]
   [:background-overlay {:optional true} :boolean]
   [:animation {:optional true} schema:animation]
   [:position-relative-to {:optional true} [:maybe ::sm/uuid]]])

(def schema:toggle-overlay-interaction
  [:map {:title "ToggleOverlayInteraction"}
   [:action-type [:= :toggle-overlay]]
   [:event-type [::sm/one-of event-types]]
   [:overlay-position {:optional true} ::gpt/point]
   [:overlay-pos-type {:optional true} [::sm/one-of overlay-positioning-types]]
   [:destination {:optional true} [:maybe ::sm/uuid]]
   [:close-click-outside {:optional true} :boolean]
   [:background-overlay {:optional true} :boolean]
   [:animation {:optional true} schema:animation]
   [:position-relative-to {:optional true} [:maybe ::sm/uuid]]])

(def schema:close-overlay-interaction
  [:map {:title "CloseOverlayInteraction"}
   [:action-type [:= :close-overlay]]
   [:event-type [::sm/one-of event-types]]
   [:destination {:optional true} [:maybe ::sm/uuid]]
   [:animation {:optional true} schema:animation]
   [:position-relative-to {:optional true} [:maybe ::sm/uuid]]])

(def schema:prev-scren-interaction
  [:map {:title "PrevScreenInteraction"}
   [:action-type [:= :prev-screen]]
   [:event-type [::sm/one-of event-types]]])

(def schema:open-url-interaction
  [:map {:title "OpenUrlInteraction"}
   [:action-type [:= :open-url]]
   [:event-type [::sm/one-of event-types]]
   [:url :string]])

;; Figma #10: change-to variant action (interactive components). Swaps the
;; variant of the target component instance on trigger. Depends on #1 typed
;; component properties — :change-to-props mirrors the instance's
;; component-property-values shape (property-name -> value).
(def schema:change-to-interaction
  [:map {:title "ChangeToInteraction"}
   [:action-type [:= :change-to]]
   [:event-type [::sm/one-of event-types]]
   [:change-to-variant-id {:optional true} [:maybe ::sm/uuid]]
   [:change-to-props {:optional true} [:map-of :string :any]]
   [:animation {:optional true} schema:animation]])

;; Figma #73: swap-overlay replaces the currently-open overlay with another
;; overlay frame, reusing the overlay positioning settings. :destination is the
;; new overlay frame id; overlay opts are authored exactly like open-overlay.
(def schema:swap-overlay-interaction
  [:map {:title "SwapOverlayInteraction"}
   [:action-type [:= :swap-overlay]]
   [:event-type [::sm/one-of event-types]]
   [:overlay-position {:optional true} ::gpt/point]
   [:overlay-pos-type {:optional true} [::sm/one-of overlay-positioning-types]]
   [:destination {:optional true} [:maybe ::sm/uuid]]
   [:close-click-outside {:optional true} :boolean]
   [:background-overlay {:optional true} :boolean]
   [:animation {:optional true} schema:animation]
   [:position-relative-to {:optional true} [:maybe ::sm/uuid]]])

;; Figma #73: scroll-to scrolls the viewport to an object within a top-level
;; frame. :destination holds the target shape id (a frame or any descendant).
(def schema:scroll-to-interaction
  [:map {:title "ScrollToInteraction"}
   [:action-type [:= :scroll-to]]
   [:event-type [::sm/one-of event-types]]
   [:destination {:optional true} [:maybe ::sm/uuid]]
   [:scroll-to-target {:optional true} [:maybe ::sm/uuid]]
   [:animation {:optional true} schema:animation]])

(def schema:interaction
  [:schema {:title "Interaction"
            :gen/gen (sg/one-of (sg/generator schema:navigate-interaction)
                                (sg/generator schema:open-overlay-interaction)
                                (sg/generator schema:close-overlay-interaction)
                                (sg/generator schema:toggle-overlay-interaction)
                                (sg/generator schema:prev-scren-interaction)
                                (sg/generator schema:open-url-interaction)
                                (sg/generator schema:change-to-interaction)
                                (sg/generator schema:swap-overlay-interaction)
                                (sg/generator schema:scroll-to-interaction))}
   [:and
    schema:generic-interaction-attrs
    [:multi {:dispatch :action-type :title "InteractionAttrs"}
     [:navigate schema:navigate-interaction]
     [:open-overlay schema:open-overlay-interaction]
     [:toggle-overlay schema:toggle-overlay-interaction]
     [:close-overlay schema:close-overlay-interaction]
     [:prev-screen schema:prev-scren-interaction]
     [:open-url schema:open-url-interaction]
     [:change-to schema:change-to-interaction]
     [:swap-overlay schema:swap-overlay-interaction]
     [:scroll-to schema:scroll-to-interaction]]]])

(def check-interaction
  (sm/check-fn schema:interaction))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def default-interaction
  {:event-type :click
   :action-type :navigate
   :destination nil
   :position-relative-to nil
   :preserve-scroll false})

(def default-delay 600)

;; -- Helpers for interaction

(declare calc-overlay-pos-initial)
(declare allowed-animation?)

(defn set-event-type
  [interaction event-type shape]
  (assert (check-interaction interaction))
  (assert (contains? event-types event-type)
          "should be a valid event type")

  (assert (or (not= event-type :after-delay)
              (cfh/frame-shape? shape))
          "the `:after-delay` event type incompatible with not frame shapes")

  (if (= (:event-type interaction) event-type)
    interaction
    (case event-type

      :after-delay
      (assoc interaction
             :event-type event-type
             :delay (get interaction :delay default-delay))

      (assoc interaction
             :event-type event-type))))

(defn set-action-type
  [interaction action-type]
  (assert (check-interaction interaction))
  (assert (contains? action-types action-type)
          "Should be a valid event type")

  (let [new-interaction
        (if (= (:action-type interaction) action-type)
          interaction
          (case action-type
            :navigate
            (assoc interaction
                   :action-type action-type
                   :destination (get interaction :destination)
                   :preserve-scroll (get interaction :preserve-scroll false))

            (:open-overlay :toggle-overlay)
            (let [overlay-pos-type (get interaction :overlay-pos-type :center)
                  overlay-position (get interaction :overlay-position (gpt/point 0 0))]
              (assoc interaction
                     :action-type action-type
                     :overlay-pos-type overlay-pos-type
                     :overlay-position overlay-position))

            :close-overlay
            (assoc interaction
                   :action-type action-type
                   :destination (get interaction :destination))

            :prev-screen
            (assoc interaction
                   :action-type action-type)

            :open-url
            (assoc interaction
                   :action-type action-type
                   :url (get interaction :url ""))

            ;; Figma #10: change-to variant action. Carry over any previously
            ;; authored variant id / props so toggling the action type keeps state.
            :change-to
            (assoc interaction
                   :action-type action-type
                   :change-to-variant-id (get interaction :change-to-variant-id)
                   :change-to-props (get interaction :change-to-props))

            ;; Figma #73: swap-overlay reuses the overlay positioning settings,
            ;; mirroring the :open-overlay / :toggle-overlay defaults.
            :swap-overlay
            (let [overlay-pos-type (get interaction :overlay-pos-type :center)
                  overlay-position (get interaction :overlay-position (gpt/point 0 0))]
              (assoc interaction
                     :action-type action-type
                     :overlay-pos-type overlay-pos-type
                     :overlay-position overlay-position
                     :destination (get interaction :destination)))

            ;; Figma #73: scroll-to carries the target shape id in :destination
            ;; (a frame or any descendant of a top-level frame).
            :scroll-to
            (assoc interaction
                   :action-type action-type
                   :destination (get interaction :destination))))]

    (cond-> new-interaction
      (not (allowed-animation? action-type
                               (-> new-interaction :animation :animation-type)))
      (dissoc :animation-type :animation))))

;; FIXME: should be renamed to has-delay?

(defn has-delay
  [interaction]
  (= (:event-type interaction) :after-delay))

(defn set-delay
  [interaction delay]
  (assert (check-interaction interaction))
  (assert (sm/check-safe-int delay))
  (assert (has-delay interaction)
          "expected compatible interaction event type")

  (assoc interaction :delay delay))

;; FIXME: rename to proper name, very confusing one because it does
;; not checks if interaction has distination, it checks if it can have
;; one.

(defn has-destination
  [interaction]
  (#{:navigate :open-overlay :toggle-overlay :close-overlay
    ;; Figma #73: swap-overlay (new overlay frame) + scroll-to (target shape)
    ;; both carry a destination id.
    :swap-overlay :scroll-to}
   (:action-type interaction)))

(defn destination?
  [interaction]
  (and (has-destination interaction)
       (some? (:destination interaction))))

(defn set-destination
  [interaction destination]
  (assert (check-interaction interaction))
  (assert (has-destination interaction)
          "expected compatible interaction event type")

  (cond-> interaction
    :always
    (assoc :destination destination)

    (or (= (:action-type interaction) :open-overlay)
        (= (:action-type interaction) :toggle-overlay))
    (assoc :overlay-pos-type :center
           :overlay-position (gpt/point 0 0))))

(defn has-preserve-scroll
  [interaction]
  (= (:action-type interaction) :navigate))

(defn set-preserve-scroll
  [interaction preserve-scroll]

  (assert (check-interaction interaction))
  (assert (boolean? preserve-scroll)
          "expected boolean for `preserve-scroll`")
  (assert (has-preserve-scroll interaction)
          "expected compatible interaction map with preserve-scroll")

  (assoc interaction :preserve-scroll preserve-scroll))

(defn has-url
  [interaction]
  (= (:action-type interaction) :open-url))

(defn set-url
  [interaction url]

  (assert (check-interaction interaction))
  (assert (string? url)
          "expected a string for `url`")
  (assert (has-url interaction)
          "expected compatible interaction map with url param")

  (assoc interaction :url url))

;; Figma #73: per-interaction enable/disable without deleting.
;; Absent :disabled = enabled (existing behavior unchanged).
(defn disabled?
  [interaction]
  (true? (:disabled interaction)))

(defn set-disabled
  [interaction disabled]

  (assert (check-interaction interaction))
  (assert (boolean? disabled)
          "expected a boolean for `disabled`")

  (assoc interaction :disabled disabled))

;; Figma #33: key-down trigger filter. :key-code is a key name like "Enter"
;; or "a" (matching `KeyboardEvent.key`); nil/absent = any key fires.
(defn has-key-code?
  [interaction]
  (= (:event-type interaction) :key-down))

(defn set-key-code
  [interaction key-code]

  (assert (check-interaction interaction))
  (assert (or (nil? key-code) (string? key-code))
          "expected a string (or nil) for `key-code`")
  (assert (has-key-code? interaction)
          "expected compatible interaction map with key-code param")

  (assoc interaction :key-code key-code))

;; Figma #10: change-to variant action.
(defn has-change-to?
  [interaction]
  (= (:action-type interaction) :change-to))

(defn set-change-to-variant
  [interaction variant-id]

  (assert (check-interaction interaction))
  (assert (or (nil? variant-id) (uuid? variant-id))
          "expected a uuid (or nil) for `change-to-variant-id`")
  (assert (has-change-to? interaction)
          "expected compatible interaction map with change-to action")

  (assoc interaction :change-to-variant-id variant-id))

(defn set-change-to-props
  [interaction props]

  (assert (check-interaction interaction))
  (assert (map? props)
          "expected a map for `change-to-props`")
  (assert (has-change-to? interaction)
          "expected compatible interaction map with change-to action")

  (assoc interaction :change-to-props props))

;; Figma #34: cubic-bezier control points, only meaningful when :easing
;; is :custom-bezier. Stored in the animation map alongside :easing.
(defn has-bezier-ctrl?
  [interaction]
  (= (-> interaction :animation :easing) :custom-bezier))

(defn set-bezier-ctrl
  [interaction bezier-ctrl]

  (assert (check-interaction interaction))
  (assert (map? bezier-ctrl)
          "expected a map with :x1 :y1 :x2 :y2 for `bezier-ctrl`")
  (assert (has-bezier-ctrl? interaction)
          "expected compatible interaction map with custom-bezier easing")

  (update interaction :animation assoc :bezier-ctrl bezier-ctrl))

(defn has-overlay-opts
  [interaction]
  (#{:open-overlay :toggle-overlay
    ;; Figma #73: swap-overlay authors the same overlay positioning settings.
    :swap-overlay}
   (:action-type interaction)))

(defn set-overlay-pos-type
  [interaction overlay-pos-type shape objects]

  (assert (check-interaction interaction))

  (assert (contains? overlay-positioning-types overlay-pos-type)
          "expected valid overlay positioning type")
  (assert (has-overlay-opts interaction)
          "expected compatible interaction map")

  (assoc interaction
         :overlay-pos-type overlay-pos-type
         :overlay-position (calc-overlay-pos-initial (:destination interaction)
                                                     shape
                                                     objects
                                                     overlay-pos-type)))
(defn toggle-overlay-pos-type
  [interaction overlay-pos-type shape objects]

  (assert (check-interaction interaction))
  (assert (contains? overlay-positioning-types overlay-pos-type)
          "expected valid overlay positioning type")
  (assert (has-overlay-opts interaction)
          "expected compatible interaction map")

  (let [new-pos-type (if (= (:overlay-pos-type interaction) overlay-pos-type)
                       :manual
                       overlay-pos-type)]
    (assoc interaction
           :overlay-pos-type new-pos-type
           :overlay-position (calc-overlay-pos-initial (:destination interaction)
                                                       shape
                                                       objects
                                                       new-pos-type))))
(defn set-overlay-position
  [interaction overlay-position]

  (assert (check-interaction interaction))
  (assert (gpt/point? overlay-position)
          "expected valid overlay position")
  (assert (has-overlay-opts interaction)
          "expected compatible interaction map")


  (assoc interaction
         :overlay-pos-type :manual
         :overlay-position overlay-position))

(defn set-close-click-outside
  [interaction close-click-outside]

  (assert (check-interaction interaction))
  (assert (boolean? close-click-outside)
          "expected boolean value for `close-click-outside`")
  (assert (has-overlay-opts interaction)
          "expected compatible interaction map")

  (assoc interaction :close-click-outside close-click-outside))

(defn set-background-overlay
  [interaction background-overlay]

  (assert (check-interaction interaction))
  (assert (boolean? background-overlay)
          "expected boolean value for `background-overlay`")
  (assert (has-overlay-opts interaction)
          "expected compatible interaction map")

  (assoc interaction :background-overlay background-overlay))

(defn set-position-relative-to
  [interaction position-relative-to]

  (assert (check-interaction interaction))
  (assert (or (nil? position-relative-to)
              (uuid? position-relative-to))
          "expected valid uuid for `position-relative-to`")
  (assert (has-overlay-opts interaction)
          "expected compatible interaction map")

  (assoc interaction :position-relative-to position-relative-to))

(defn- calc-overlay-pos-initial
  [destination shape objects overlay-pos-type]
  (if (and (= overlay-pos-type :manual) (some? destination))
    (let [dest-frame   (get objects destination)
          overlay-size (:selrect dest-frame)
          orig-frame   (if (= (:type shape) :frame)
                         shape
                         (get objects (:frame-id shape)))
          frame-size   (:selrect orig-frame)]
      (gpt/point (/ (- (:width frame-size) (:width overlay-size)) 2)
                 (/ (- (:height frame-size) (:height overlay-size)) 2)))
    (gpt/point 0 0)))

(defn calc-overlay-position
  [interaction         ; interaction data
   shape               ; Shape with the interaction
   objects             ; the objects tree
   relative-to-shape   ; the interaction position is realtive to this shape
   base-frame          ; the base frame of the current interaction
   dest-frame          ; the frame to display with this interaction
   frame-offset]       ; if this interaction starts in a frame opened
                       ; on another interaction, this is the position
                       ; of that frame
  (assert (check-interaction interaction))
  (assert (has-overlay-opts interaction)
          "expected compatible interaction map")

  (let [;; When the interactive item is inside a nested frame we need to add to the offset the position
        ;; of the parent-frame otherwise the position won't match
        shape-frame (cfh/get-frame objects shape)

        frame-offset (if (or (not= :manual (:overlay-pos-type interaction))
                             (nil? shape-frame)
                             (cfh/is-direct-child-of-root? shape-frame)
                             (cfh/root? shape-frame))
                       frame-offset
                       (gpt/add frame-offset (gpt/point shape-frame)))]

    (if (nil? dest-frame)
      [(gpt/point 0 0) [:top :left]]
      (let [overlay-size           (gsb/get-object-bounds objects dest-frame)
            base-frame-size        (:selrect base-frame)
            relative-to-shape-size (:selrect relative-to-shape)
            relative-to-adjusted-to-base-frame {:x (- (:x relative-to-shape-size) (:x base-frame-size))
                                                :y (- (:y relative-to-shape-size) (:y base-frame-size))}
            relative-to-is-auto?   (and (nil? (:position-relative-to interaction)) (not= :manual (:overlay-pos-type interaction)))
            base-position          (if relative-to-is-auto?
                                     {:x 0 :y 0}
                                     {:x (+ (:x frame-offset)
                                            (:x relative-to-adjusted-to-base-frame))
                                      :y (+ (:y frame-offset)
                                            (:y relative-to-adjusted-to-base-frame))})
            overlay-position       (:overlay-position interaction)
            overlay-position       (if (= (:type relative-to-shape) :frame)
                                     overlay-position
                                     {:x (- (:x overlay-position) (:x relative-to-adjusted-to-base-frame))
                                      :y (- (:y overlay-position) (:y relative-to-adjusted-to-base-frame))})]

        (case (:overlay-pos-type interaction)
          :center
          [(gpt/point (+ (:x base-position) (/ (- (:width relative-to-shape-size) (:width overlay-size)) 2))
                      (+ (:y base-position) (/ (- (:height relative-to-shape-size) (:height overlay-size)) 2)))
           [:center :center]]

          :top-left
          [(gpt/point (:x base-position) (:y base-position))
           [:top :left]]

          :top-right
          [(gpt/point (+ (:x base-position) (- (:width relative-to-shape-size) (:width overlay-size)))
                      (:y base-position))
           [:top :right]]

          :top-center
          [(gpt/point (+ (:x base-position) (/ (- (:width relative-to-shape-size) (:width overlay-size)) 2))
                      (:y base-position))
           [:top :center]]

          :bottom-left
          [(gpt/point (:x base-position)
                      (+ (:y base-position) (- (:height relative-to-shape-size) (:height overlay-size))))
           [:bottom :left]]

          :bottom-right
          [(gpt/point (+ (:x base-position) (- (:width relative-to-shape-size) (:width overlay-size)))
                      (+ (:y base-position) (- (:height relative-to-shape-size) (:height overlay-size))))
           [:bottom :right]]

          :bottom-center
          [(gpt/point (+ (:x base-position) (/ (- (:width relative-to-shape-size) (:width overlay-size)) 2))
                      (+ (:y base-position) (- (:height relative-to-shape-size) (:height overlay-size))))
           [:bottom :center]]

          :manual
          [(gpt/point (+ (:x base-position) (:x overlay-position))
                      (+ (:y base-position) (:y overlay-position)))
           [:top :left]])))))

(defn has-animation?
  [interaction]
  (#{:navigate :open-overlay :close-overlay :toggle-overlay
    ;; Figma #73: swap-overlay transitions like an overlay animation.
    ;; Figma #10/#11: change-to may animate (notably with :smart-animate).
    :swap-overlay :change-to}
   (:action-type interaction)))

(defn allow-push?
  [action-type]
  ; Push animation is not allowed for overlay actions
  (= :navigate action-type))

;; Figma #11: smart animate is allowed for navigation, change-to (variant
;; transitions) and swap-overlay. Other actions fall back to dissolve/slide.
(defn allow-smart-animate?
  [action-type]
  (#{:navigate :change-to :swap-overlay} action-type))

(defn allowed-animation?
  [action-type animation-type]
  ; Some specific combinations are forbidden, but may occur if the action type
  ; is changed from a type that allows the animation to another one that doesn't.
  ; Currently the only cases are an overlay action with push animation, and a
  ; non-smart-animate-capable action with smart-animate.
  (and (or (not= animation-type :push)
           (allow-push? action-type))
       (or (not= animation-type :smart-animate)
           (allow-smart-animate? action-type))))

(defn set-animation-type
  [interaction animation-type]

  (assert (check-interaction interaction))
  (assert (or (nil? animation-type)
              (contains? animation-types animation-type))
          "expected valid value for `animation-type`")
  (assert (has-animation? interaction)
          "expected interaction map compatible with animation")
  (assert (allowed-animation? (:action-type interaction) animation-type)
          "expected allowed animation type")

  (if (= (-> interaction :animation :animation-type) animation-type)
    interaction
    (if (nil? animation-type)
      (dissoc interaction :animation)
      (cond-> interaction
        :always
        (update :animation assoc :animation-type animation-type)

        (= animation-type :dissolve)
        (update :animation assoc
                :duration (get-in interaction [:animation :duration] 300)
                :easing (get-in interaction [:animation :easing] :linear))

        (= animation-type :slide)
        (update :animation assoc
                :duration (get-in interaction [:animation :duration] 300)
                :easing (get-in interaction [:animation :easing] :linear)
                :way (get-in interaction [:animation :way] :in)
                :direction (get-in interaction [:animation :direction] :right)
                :offset-effect (get-in interaction [:animation :offset-effect] false))

        (= animation-type :push)
        (update :animation assoc
                :duration (get-in interaction [:animation :duration] 300)
                :easing (get-in interaction [:animation :easing] :linear)
                :direction (get-in interaction [:animation :direction] :right))

        ;; Figma #11: smart animate. Duration + easing authored here; the
        ;; matched-property tweening runtime is deferred (see viewer dispatch).
        (= animation-type :smart-animate)
        (update :animation assoc
                :duration (get-in interaction [:animation :duration] 300)
                :easing (get-in interaction [:animation :easing] :linear))))))

(defn has-duration?
  [interaction]
  (#{:dissolve :slide :push :smart-animate} (-> interaction :animation :animation-type)))

(defn set-duration
  [interaction duration]

  (assert (check-interaction interaction))
  (assert (sm/check-safe-int duration))
  (assert (has-duration? interaction)
          "expected compatible interaction map")

  (update interaction :animation assoc :duration duration))

(defn has-easing?
  [interaction]
  (#{:dissolve :slide :push :smart-animate} (-> interaction :animation :animation-type)))

(defn set-easing
  [interaction easing]

  (assert (check-interaction interaction))
  (assert (contains? easing-types easing)
          "expected valid easing")
  (assert (has-easing? interaction)
          "expected compatible interaction map")

  (update interaction :animation assoc :easing easing))

(defn has-way?
  [interaction]
  ; Way is ignored in slide animations of overlay actions
  (and (= (:action-type interaction) :navigate)
       (= (-> interaction :animation :animation-type) :slide)))

(defn set-way
  [interaction way]

  (assert (check-interaction interaction))
  (assert (contains? way-types way)
          "expected valid way")
  (assert (has-way? interaction)
          "expected compatible interaction map")

  (update interaction :animation assoc :way way))

(defn has-direction?
  [interaction]
  (#{:slide :push} (-> interaction :animation :animation-type)))

(defn set-direction
  [interaction direction]

  (assert (check-interaction interaction))
  (assert (contains? direction-types direction)
          "expected valid direction")

  (assert (has-direction? interaction)
          "expected compatible interaction map")

  (update interaction :animation assoc :direction direction))

(defn invert-direction
  [animation]
  (assert (or (nil? animation)
              (check-animation! animation))
          "expected valid animation map")

  (case (:direction animation)
    :right
    (assoc animation :direction :left)
    :left
    (assoc animation :direction :right)
    :up
    (assoc animation :direction :down)
    :down
    (assoc animation :direction :up)

    animation))

(defn has-offset-effect?
  [interaction]
  ;; Offset-effect is ignored in slide animations of overlay actions
  (and (= (:action-type interaction) :navigate)
       (= (-> interaction :animation :animation-type) :slide)))

(defn set-offset-effect
  [interaction offset-effect]

  (assert (check-interaction interaction))
  (assert (boolean? offset-effect)
          "expected valid boolean for `offset-effect`")
  (assert (has-offset-effect? interaction)
          "expected compatible interaction map")

  (update interaction :animation assoc :offset-effect offset-effect))

(defn dest-to?
  "Check if the interaction has the given frame as destination."
  [interaction frame-id]
  (and (has-destination interaction)
       (= (:destination interaction) frame-id)))

(defn navs-to?
  "Check if the interaction is a navigation to the given frame."
  [interaction frame-id]
  (and (= (:action-type interaction) :navigate)
       (= (:destination interaction) frame-id)))

;; -- Helpers for interactions

(defn add-interaction
  [interactions interaction]
  (conj (or interactions []) interaction))

(defn remove-interaction
  [interactions index]
  (let [interactions (or interactions [])]
    (into (subvec interactions 0 index)
          (subvec interactions (inc index)))))

(defn update-interaction
  [interactions index update-fn]
  (update interactions index update-fn))

(defn remap-interactions
  "Update all interactions whose destination points to a shape in the
  map to the new id. And remove the ones whose destination does not exist
  in the map nor in the objects tree."
  [interactions ids-map objects]
  (when (some? interactions)
    (let [xform (comp (filter (fn [interaction]
                                (let [destination (:destination interaction)]
                                  (or (nil? destination)
                                      (contains? ids-map destination)
                                      (contains? objects destination)))))
                      (map (fn [interaction]
                             (d/update-when interaction :destination #(get ids-map % %)))))]
      (into [] xform interactions))))

(defn remove-interactions
  "Remove all interactions that the fn returns true."
  [f interactions]
  (-> (d/removev f interactions)
      not-empty))

(defn actionable?
  "Check if there is any interaction that is clickable by the user"
  [interactions]
  (some #(= (:event-type %) :click) interactions))

(defn flow-origin?
  "Check if there is any interaction that is the start or the continuation of a flow"
  [interactions]
  (some #(and (#{:navigate :open-overlay :toggle-overlay :close-overlay} (:action-type %))
              (some? (:destination %)))
        interactions))

(defn flow-to?
  "Check if there is any interaction that flows into the given frame"
  [interactions frame-id]
  (some #(and (#{:navigate :open-overlay :toggle-overlay :close-overlay} (:action-type %))
              (= (:destination %) frame-id))
        interactions))
