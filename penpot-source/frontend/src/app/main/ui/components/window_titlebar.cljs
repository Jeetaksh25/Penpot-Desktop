;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.components.window-titlebar
  (:require-macros [app.main.style :as stl])
  (:require
   ["@tauri-apps/api/window" :refer [getCurrentWindow]]
   ["lucide-react" :refer [Minus Moon Settings Square Sun X]]
   [app.main.data.modal :as modal]
   [app.main.data.profile :as dp]
   [app.main.store :as st]
   [app.util.i18n :as i18n :refer [tr]]
   ;; Side-effect require: registers the :ai-settings modal component into
   ;; `data.modal/components` (the `ai-settings*` mf/defc uses
   ;; `::mf/register modal/components`). The gear button below emits
   ;; `(modal/show {:type :ai-settings})` from EVERY route — including the
   ;; dashboard, where the workspace-only `ai_bar.cljs` require (the previous
   ;; sole registration point) is never loaded. Without this the modal show
   ;; silently renders nothing on the dashboard.
   [app.main.ui.workspace.ai-settings]
   [rumext.v2 :as mf]))

(defn- get-window []
  (try
    (getCurrentWindow)
    (catch :default e
      (js/console.warn "Failed to get current window:" e)
      nil)))

(defn- toggle-theme
  [profile]
  ;; Binary light/dark toggle (deliberately simpler than `dp/toggle-theme`,
  ;; which cycles dark -> light -> system -> dark). We emit the FULL profile
  ;; (with the new :theme) so `dp/update-profile`'s `check-profile` schema
  ;; validation passes — mirroring settings/options.cljs — then persist.
  ;; `theme/use-initialize` (mounted in `app`) watches `[:profile :theme]`
  ;; and swaps the body `.light`/`.default` class, so the whole app — and the
  ;; `--color-*` custom properties every surface consumes — re-themes.
  (let [is-light   (= (:theme profile) "light")
        new-theme  (if is-light "dark" "light")]
    (st/emit! (dp/update-profile (assoc profile :theme new-theme))
              (dp/persist-profile))))

(mf/defc window-titlebar*
  {::mf/memo true
   ::mf/private true}
  [{:keys [profile] :as props}]
  (let [theme        (:theme profile)
        is-light     (= theme "light")

        on-toggle    (mf/use-fn
                      (mf/deps profile)
                      #(toggle-theme profile))

        on-drag      (mf/use-fn
                      (fn [e]
                        (when (= (.-button ^js e) 0)
                          (when-let [^js w (get-window)]
                            (try
                              (.startDragging w)
                              (catch :default err
                                (js/console.warn "startDragging failed:" err)))))))

        on-minimize  (mf/use-fn
                      (fn []
                        (when-let [^js w (get-window)]
                          (try
                            (.minimize w)
                            (catch :default err
                              (js/console.warn "minimize failed:" err))))))
        on-maximize  (mf/use-fn
                      (fn []
                        (when-let [^js w (get-window)]
                          (try
                            (some-> (.isMaximized w)
                                    (.then (fn [maxed?]
                                             (if maxed?
                                               (.unmaximize ^js w)
                                               (.maximize ^js w))))
                                    (.catch (fn [_]
                                              (try (.toggleMaximize ^js w) (catch :default _ nil)))))
                            (catch :default err
                              (js/console.warn "maximize failed:" err))))))
        on-close     (mf/use-fn
                      (fn []
                        (when-let [^js w (get-window)]
                          (try
                            (.close w)
                            (catch :default err
                              (js/console.warn "close failed:" err))))))]

    [:div {:class (stl/css :window-titlebar)}
     ;; Left: drag region. `data-tauri-drag-region` makes Tauri handle window
     ;; dragging AND double-click-to-maximize natively (requires the
     ;; `core:window:allow-start-dragging` + `allow-toggle-maximize`
     ;; permissions). Interactive controls live in a SIBLING without the
     ;; attribute, so clicking them never starts a drag.
     [:div {:class (stl/css :titlebar-drag)
            :data-tauri-drag-region "true"
            :on-mouse-down on-drag}
      [:span {:class (stl/css :titlebar-title)} "Ovion Desktop"]]

     ;; Right: settings gear, theme toggle, then a gap, then the window controls.
     [:div {:class (stl/css :titlebar-controls)}
      [:button {:class (stl/css :titlebar-btn :titlebar-settings-toggle)
                :type "button"
                :aria-label (tr "titlebar.settings")
                :title (tr "titlebar.settings")
                :on-click #(st/emit! (modal/show {:type :ai-settings}))}
       [:> Settings {:size 16 :color "currentColor"}]]

      [:button {:class (stl/css :titlebar-btn :titlebar-theme-toggle)
                :type "button"
                :aria-label (if is-light "Switch to dark theme" "Switch to light theme")
                :title (if is-light "Dark theme" "Light theme")
                :on-click on-toggle}
       (if is-light
         [:> Moon {:size 16 :color "currentColor"}]
         [:> Sun {:size 16 :color "currentColor"}])]

      [:button {:class (stl/css :titlebar-btn)
                :type "button"
                :aria-label "Minimize"
                :title "Minimize"
                :on-click on-minimize}
       [:> Minus {:size 16 :color "currentColor"}]]

      [:button {:class (stl/css :titlebar-btn)
                :type "button"
                :aria-label "Maximize"
                :title "Maximize"
                :on-click on-maximize}
       [:> Square {:size 13 :color "currentColor"}]]

      [:button {:class (stl/css :titlebar-btn :titlebar-btn-close)
                :type "button"
                :aria-label "Close"
                :title "Close"
                :on-click on-close}
       [:> X {:size 17 :color "currentColor"}]]]]))