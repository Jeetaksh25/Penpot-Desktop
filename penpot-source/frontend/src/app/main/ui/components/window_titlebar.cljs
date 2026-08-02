;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.components.window-titlebar
  (:require-macros [app.main.style :as stl])
  (:require
   ["@tauri-apps/api/window" :refer [getCurrentWindow]]
   ["lucide-react" :refer [Minus Moon Square Sun X]]
   [app.main.data.profile :as dp]
   [app.main.store :as st]
   [rumext.v2 :as mf]))

;; The Tauri webview window handle. This ns is only loaded inside the
;; desktop webview (it's required by the root `app.main.ui/app`), so
;; `getCurrentWindow` is safe to call at load time. `defonce` keeps it
;; stable across figwheel/reload cycles.
(defonce ^:private current-window (getCurrentWindow))

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

        on-minimize  (mf/use-fn #(.minimize current-window))
        on-maximize  (mf/use-fn #(.toggleMaximize current-window))
        on-close     (mf/use-fn #(.close current-window))]

    [:div {:class (stl/css :window-titlebar)}
     ;; Left: drag region. `data-tauri-drag-region` makes Tauri handle window
     ;; dragging AND double-click-to-maximize natively (requires the
     ;; `core:window:allow-start-dragging` + `allow-toggle-maximize`
     ;; permissions). Interactive controls live in a SIBLING without the
     ;; attribute, so clicking them never starts a drag.
     [:div {:class (stl/css :titlebar-drag)
            :data-tauri-drag-region "true"}
      [:span {:class (stl/css :titlebar-title)} "Oriole Desktop"]]

     ;; Right: theme toggle, then a gap, then the window controls.
     [:div {:class (stl/css :titlebar-controls)}
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