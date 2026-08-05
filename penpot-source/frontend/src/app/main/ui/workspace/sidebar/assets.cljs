;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.assets
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.components-list :as ctkl]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.assets :as dwa]
   [app.main.data.workspace.ecommerce-kit :as eckit]
   [app.main.data.workspace.material-kit :as m3kit]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.context-menu-a11y :refer [context-menu*]]
   [app.main.ui.components.search-bar :refer [search-bar*]]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.icons :as deprecated-icon]
   [app.main.ui.workspace.sidebar.assets.common :as cmm]
   [app.main.ui.workspace.sidebar.assets.file-library :refer [file-library*]]
   [app.main.data.workspace.stock-assets :as stock]
   [app.main.data.workspace.storybook :as sb]
   [app.util.dom :as dom]
   [app.util.dom.dnd :as dnd]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [promesa.core :as p]
   [rumext.v2 :as mf]))

(mf/defc assets-libraries*
  {::mf/wrap [mf/memo]
   ::mf/private true}
  [{:keys [filters]}]
  (let [file-id   (mf/use-ctx ctx/current-file-id)
        files     (mf/deref refs/files)
        libraries (mf/with-memo [files file-id]
                    (->> (refs/select-libraries files file-id)
                         (vals)
                         (remove #(= file-id (:id %)))
                         (map (fn [file]
                                (update file :data dissoc :pages-index)))
                         (sort-by #(str/lower (:name %)))))]

    (for [file libraries]
      [:> file-library*
       {:key (dm/str (:id file))
        :file file
        :is-local false
        :is-default-open false
        :filters filters}])))

(def ^:private ref:local-library
  (l/derived (fn [file]
               (update file :data dissoc :pages-index))
             refs/file))

(mf/defc assets-local-library*
  {::mf/private true}
  [{:keys [filters]}]
  (let [file (mf/deref ref:local-library)]
    [:> file-library*
     {:file file
      :is-local true
      :is-default-open true
      :filters filters}]))

(defn- toggle-values
  [v a b]
  (if (= v a) b a))

;; Per-file, session-scoped (in-memory only) so the search term and section
;; filter survive switching between the Layers and Assets sidebar tabs without
;; leaking across files or persisting across reloads.
(defonce ^:private session-filters*
  (atom {}))

;; Figma #70: visual + semantic asset search scaffold.
;;
;; A flat cross-library search panel. Today it falls back to plain substring
;; matching over component names — the same heuristic a future embedding-based
;; semantic search would replace. The embedding storage + similarity query
;; needs Rust (src-tauri embeddings, out of scope under the no-build
;; constraint) and is DEFERRED; this UI is the scaffold the semantic results
;; will populate. Purely additive: a new collapsible section above the
;; libraries, never modifies the existing assets-toolbox* search-bar flow.
(mf/defc visual-search*
  {::mf/private true}
  [{:keys [file-id]}]
  (let [open* (mf/use-state false)
        open? (deref open*)
        term* (mf/use-state "")
        term  (deref term*)
        libs  (mf/deref refs/libraries)

        ;; Flat component index across every loaded library (local + linked).
        ;; Each hit carries enough to locate it in the sectioned view below.
        results
        (mf/with-memo [libs term file-id]
          (let [needle (str/lower term)]
            (->> (vals libs)
                 (mapcat (fn [file]
                           (let [file-name (:name file)
                                 comps     (ctkl/components (:data file))]
                             (keep (fn [c]
                                     (let [cname (:name c)]
                                       (when (and (string? cname)
                                                  (or (str/empty? needle)
                                                      (str/includes? (str/lower cname) needle)))
                                         {:id        (:id c)
                                          :name      cname
                                          :file-id   (:id file)
                                          :file-name file-name})))
                                   comps))))
                 (sort-by #(str/lower (:name %)))
                 (into []))))

        toggle-open (mf/use-fn #(swap! open* not))
        on-term     (mf/use-fn (fn [e] (reset! term* (.. e -target -value))))]

    [:div {:class (stl/css :visual-search)}
     [:> title-bar* {:collapsable  true
                     :collapsed    (not open?)
                     :on-collapsed toggle-open
                     :title        (tr "workspace.assets.visual-search")
                     :class        (stl/css :title-bar)}]
     (when open?
       [:div {:class (stl/css :content)}
        [:> search-bar* {:on-change on-term
                         :value term
                         :placeholder (tr "workspace.assets.visual-search-placeholder")}]
        ;; DEFERRED: embedding-based semantic visual search needs Rust.
        [:div {:class (stl/css :visual-search-hint)}
         (tr "workspace.assets.visual-search-hint")]
        (when (seq results)
          [:div {:class (stl/css :visual-search-results)}
           (for [r results]
             [:div {:key (str (:file-id r) "-" (:id r))
                    :class (stl/css :visual-search-result)}
              [:span {:class (stl/css :visual-search-result-name)} (:name r)]
              [:span {:class (stl/css :visual-search-result-file)} (:file-name r)]])])])]))

;; ── P0.04: Built-in stock asset library (Iconify icons + Pexels photos) ──────
;;
;; A collapsible "Stock" section above the libraries, additive like
;; `visual-search*`. The user picks a category chip (Icons / Illustrations /
;; Photos), types a keyword, and gets a grid of results they can drag onto
;; the canvas.
;;
;; Drag-to-canvas reuses the EXISTING viewport on-drop infrastructure
;; (`ui.workspace.viewport.actions/on-drop`) by setting the standard
;; `text/uri-list` DnD type:
;;   • Icons/Illustrations → a `data:image/svg+xml;base64,…` URI. The
;;     viewport on-drop maps `data:image/…` lines to blobs via
;;     `wapi/data-uri->blob`, hands them to `dwm/upload-media-workspace`,
;;     which routes `image/svg+xml` blobs to `svg-uploaded` →
;;     `svg/add-svg-shapes` (the existing svg-raw shape creation path).
;;   • Photos → the Pexels `src.large` HTTP URL. The viewport on-drop maps
;;     `http` lines to `:uris`, hands them to `dwm/upload-media-workspace`
;;     → `:create-file-media-object-from-url` → `image-uploaded` (the
;;     existing image-shape-from-URL path, same one Feature 4's URL-clone
;;     uses). No new shape-creation code anywhere.
;;
;; Icons + Illustrations both hit Iconify today (the distinction is a UI
;; affordance; Illustrations is the slot a future curated illustration
;; corpus — e.g. unDraw / Storyset — will fill). Photos is Pexels, gated
;; behind a user-supplied key stored in localStorage (see
;; `data.workspace.stock-assets`); when absent the tab shows a "set your
;; Pexels key" empty state and no network call is made.
;;
;; Offline: the Rust commands keep a session-scoped in-process cache keyed
;; by query (+ page), so re-searching the same term while offline returns
;; the cached payload instantly. The `:cached` flag in the payload lets the
;; UI hint provenance.

(def ^:private stock-categories
  [[:icons         "workspace.assets.stock-icons"]
   [:illustrations "workspace.assets.stock-illustrations"]
   [:photos        "workspace.assets.stock-photos"]])

(defn- build-icon-svg
  "Build a full `<svg>…</svg>` string from an Iconify icon
  `{name, body, width, height}`. The body is the inner SVG markup
  (paths/circles/…); we wrap it with a viewBox so it scales crisply."
  [{:keys [body width height]}]
  (dm/str "<svg xmlns=\"http://www.w3.org/2000/svg\""
          " viewBox=\"0 0 " (or width 24) " " (or height 24) "\""
          " width=\"" (or width 24) "\""
          " height=\"" (or height 24) "\">"
          (or body "")
          "</svg>"))

(defn- svg->data-uri
  "Base64-encode an SVG string into a `data:image/svg+xml;base64,…` URI
  safe for `text/uri-list` DnD (no raw `<`/`#` that break some browsers'
  data-URI parsing)."
  [svg-str]
  (dm/str "data:image/svg+xml;base64,"
          (try (js/btoa svg-str) (catch :default _ ""))))

(defn- icon-drag-uri
  "The DnD payload for an icon result — a base64 SVG data URI the viewport
  on-drop turns into an svg-raw shape."
  [icon]
  (svg->data-uri (build-icon-svg icon)))

(defn- photo-drag-uri
  "The DnD payload for a Pexels photo result — the `large` HTTP URL the
  viewport on-drop uploads via `:create-file-media-object-from-url`.
  `photo` is the keywordized Pexels photo map (`js->clj :keywordize-keys`)."
  [photo]
  (let [src (:src photo)]
    (or (:large src)
        (:original src)
        (:medium src)
        "")))

(mf/defc stock-section*
  {::mf/private true}
  [{:keys [file-id]}]
  (let [open*   (mf/use-state false)
        open?   (deref open*)
        cat*    (mf/use-state :icons)
        cat     (deref cat*)
        term*   (mf/use-state "")
        term    (deref term*)
        ;; :status ∈ #{:idle :loading :ready :error}
        state*  (mf/use-state {:status :idle :data nil :error nil})
        state   (deref state*)
        pexels-key*     (mf/use-state (fn [] (stock/load-pexels-key)))
        pexels-key      (deref pexels-key*)
        show-key-input* (mf/use-state false)
        show-key-input  (deref show-key-input*)
        status          (:status state)
        data            (:data state)
        err             (:error state)

        ;; The photo corpus needs a key; preflight it so we never fire a
        ;; doomed request. Icons/Illustrations are keyless (Iconify).
        photos-need-key? (and (= cat :photos) (str/blank? pexels-key))

        toggle-open
        (mf/use-fn #(swap! open* not))

        on-cat
        (mf/use-fn
         (mf/deps cat)
         (fn [c]
           (when (not= c cat)
             (reset! cat* c)
             (reset! state* {:status :idle :data nil :error nil}))))

        on-term
        (mf/use-fn (fn [e] (reset! term* (.. e -target -value))))

        on-save-key
        (mf/use-fn
         (fn [e]
           (let [v (.. e -target -value)]
             (reset! pexels-key* v)
             (stock/save-pexels-key v))))

        toggle-key-input
        (mf/use-fn #(swap! show-key-input* not))

        do-search
        (mf/use-fn
         (mf/deps cat term pexels-key file-id)
         (fn []
           (cond
             (str/blank? term)
             (reset! state* {:status :idle :data nil :error nil})

             photos-need-key?
             (reset! state* {:status :error :data nil :error "pexels-key-missing"})

             :else
             (do
               (reset! state* {:status :loading :data nil :error nil})
               (let [p (if (= cat :photos)
                         (stock/search-photos term pexels-key 1)
                         (stock/search-icons term 64))]
                 (-> p
                     (p/then
                      (fn [js-obj]
                        (let [res (js->clj js-obj :keywordize-keys true)]
                          (reset! state* {:status :ready :data res :error nil})
                          (if (= cat :photos)
                            (stock/remember-photos! term 1 res)
                            (stock/remember-icons! term res)))))
                     (p/catch
                      (fn [cause]
                        (reset! state* {:status :error :data nil :error (str cause)})))))))))

        on-search
        (mf/use-fn
         (mf/deps [do-search])
         (fn [e]
           (dom/prevent-default e)
           (do-search)))

        on-drag-start-icon
        (mf/use-fn
         (fn [e icon]
           (let [uri (icon-drag-uri icon)]
             (dnd/set-data! e "text/uri-list" uri)
             (dnd/set-allowed-effect! e "copy"))))

        on-drag-start-photo
        (mf/use-fn
         (fn [e photo]
           (let [uri (photo-drag-uri photo)]
             (when-not (str/blank? uri)
               (dnd/set-data! e "text/uri-list" uri)
               (dnd/set-allowed-effect! e "copy")))))]

    [:div {:class (stl/css :stock-section)}
     [:> title-bar* {:collapsable  true
                     :collapsed    (not open?)
                     :on-collapsed toggle-open
                     :title        (tr "workspace.assets.stock")
                     :class        (stl/css :title-bar)}]
     (when open?
       [:div {:class (stl/css :stock-content)}
        ;; Category chips (coral accent).
        [:div {:class (stl/css :stock-chips)}
         (for [[c label-key] stock-categories]
           [:button {:key (name c)
                     :type "button"
                     :class (stl/css-case :stock-chip true
                                          :stock-chip-active (= c cat))
                     :on-click #(on-cat c)}
            (tr label-key)])]

        ;; Search input + button.
        [:form {:class (stl/css :stock-search)
                :on-submit on-search}
         [:input {:type "text"
                  :class (stl/css :stock-input)
                  :value term
                  :placeholder (tr "workspace.assets.stock-search-placeholder")
                  :on-change on-term}]
         [:button {:type "submit"
                   :class (stl/css :stock-search-btn)
                   :disabled (or (= status :loading) (str/blank? term))}
          (tr "workspace.assets.stock-search-btn")]]

        ;; Photos key gate: when the Photos tab is selected and no key is
        ;; set, show a key input (localStorage-backed) instead of firing a
        ;; doomed request.
        (when (and (= cat :photos) (or show-key-input photos-need-key?))
          [:div {:class (stl/css :stock-key-row)}
           [:input {:type "password"
                    :class (stl/css :stock-input)
                    :value pexels-key
                    :placeholder (tr "workspace.assets.stock-pexels-key-placeholder")
                    :on-change on-save-key}]
           [:button {:type "button"
                     :class (stl/css :stock-search-btn)
                     :on-click toggle-key-input}
            (tr "workspace.assets.stock-pexels-key-done")]])

        ;; Pexels key manage toggle (only relevant on the Photos tab).
        (when (and (= cat :photos) (not photos-need-key?) (not show-key-input))
          [:button {:type "button"
                    :class (stl/css :stock-key-manage)
                    :on-click toggle-key-input}
           (tr "workspace.assets.stock-pexels-key-change")])

        ;; States.
        (cond
          (= status :loading)
          [:div {:class (stl/css :stock-status)} (tr "workspace.assets.stock-loading")]

          (= status :error)
          (condp = err
            "pexels-key-missing"
            [:div {:class (stl/css :stock-status)}
             (tr "workspace.assets.stock-pexels-key-missing")]

            "pexels-key-invalid"
            [:div {:class (stl/css :stock-status)}
             (tr "workspace.assets.stock-pexels-key-invalid")]

            [:div {:class (stl/css :stock-status)}
             (tr "workspace.assets.stock-error")])

          (or (= status :idle) (and (= status :ready) (empty? (or (get data :icons) (get data :photos) []))))
          (if (str/blank? term)
            [:div {:class (stl/css :stock-status)}
             (tr "workspace.assets.stock-empty")]
            [:div {:class (stl/css :stock-status)}
             (tr "workspace.assets.stock-no-results")])

          :else
          (let [icons  (get data :icons)
                photos (get data :photos)]
            [:div {:class (stl/css :stock-grid)}
             (if (= cat :photos)
               ;; Photos grid.
               (for [photo photos]
                 (let [src   (:src photo)
                       thumb (or (:medium src) (:small src) (:tiny src))
                       alt   (or (:alt photo) (:photographer photo) "photo")
                       pid   (:id photo)]
                   [:div {:key (str "photo-" pid)
                          :class (stl/css :stock-tile :stock-photo-tile)
                          :draggable true
                          :on-drag-start #(on-drag-start-photo % photo)}
                    [:img {:src thumb :alt alt :loading "lazy"
                           :class (stl/css :stock-thumb-img)}]]))
               ;; Icons / Illustrations grid.
               (for [icon icons]
                 (let [iname (:name icon)
                       body  (:body icon)
                       w     (:width icon)
                       h     (:height icon)]
                   [:div {:key iname
                          :class (stl/css :stock-tile :stock-icon-tile)
                          :draggable true
                          :title iname
                          :on-drag-start #(on-drag-start-icon % icon)}
                    [:svg {:viewBox (dm/str "0 0 " (or w 24) " " (or h 24))
                           :width "100%" :height "100%"
                           :dangerouslySetInnerHTML #js {:__html (or body "")}}]])))]))])]))

;; ── P2.11: Material 3 design kit injection (additive) ───────────────────────
;;
;; A single action row in the Assets panel. Clicking it emits
;; `m3kit/inject-material-kit`, which adds the M3 color-role tokens as
;; library color styles + a 'Material 3' board of M3 components via the
;; existing `design-gen/apply-design-spec` pipeline, in one undo batch.
;; Purely additive — files without the kit are byte-identical. The
;; idempotency guard in the event skips a second injection.
;;
;; Lucide `package` icon, inline SVG (stroke-width 2, currentColor), coral
;; accent #f28b82. Reduced-motion: no size/scale transitions on hover/press
;; — only a calm box-shadow / color swap.
(mf/defc material-kit-section*
  {::mf/private true}
  []
  (let [busy* (mf/use-state false)
        busy  (deref busy*)
        on-add (mf/use-fn
                (mf/deps [busy])
                (fn []
                  (when-not ^boolean busy
                    (reset! busy* true)
                    (st/emit! (m3kit/inject-material-kit))
                    ;; Reset after a tick so the button can recover; the
                    ;; event is fire-and-forget (no completion signal).
                    (js/setTimeout #(reset! busy* false) 600))))]
    [:div {:class (stl/css :m3-kit-section)}
     [:button {:type           "button"
               :class          (stl/css-case :m3-kit-button true
                                                 :m3-kit-button-busy busy)
               :disabled       ^boolean busy
               :on-click       on-add
               :data-testid    "add-material-3-kit"
               :aria-label     (tr "workspace.assets.m3-add-aria")}
      ;; Lucide `package` icon (24x24, stroke-width 2, currentColor).
      [:svg {:viewBox "0 0 24 24" :width 18 :height 18 :fill "none"
             :stroke "currentColor" :stroke-width 2
             :stroke-linecap "round" :stroke-linejoin "round"
             :aria-hidden true}
       [:path {:d "M16.5 9.4 7.55 4.24"}]
       [:path {:d "M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"}]
       [:polyline {:points "3.27 6.96 12 12.01 20.73 6.96"}]
       [:line {:x1 12 :y1 22.08 :x2 12 :y2 12}]]
      [:span {:class (stl/css :m3-kit-label)}
       (tr "workspace.assets.m3-add")]]]))

;; ── P1.15: E-commerce design kit injection (additive) ───────────────────────
;;
;; A single action row in the Assets panel. Clicking it emits
;; `eckit/inject-ecommerce-kit`, which adds an 'E-commerce' board of
;; commerce components (hero, nav, product grid, cart, checkout, order
;; summary) via the existing `design-gen/apply-design-spec` pipeline, in
;; one undo batch. Purely additive — files without the kit are
;; byte-identical. The idempotency guard in the event skips a second
;; injection.
;;
;; Lucide `shopping-bag` icon, inline SVG (stroke-width 2, currentColor),
;; coral accent #f28b82. Reduced-motion: no size/scale transitions on
;; hover/press — only a calm box-shadow / color swap.
(mf/defc ecommerce-kit-section*
  {::mf/private true}
  []
  (let [busy* (mf/use-state false)
        busy  (deref busy*)
        on-add (mf/use-fn
                (mf/deps [busy])
                (fn []
                  (when-not ^boolean busy
                    (reset! busy* true)
                    (st/emit! (eckit/inject-ecommerce-kit))
                    ;; Reset after a tick so the button can recover; the
                    ;; event is fire-and-forget (no completion signal).
                    (js/setTimeout #(reset! busy* false) 600))))]
    [:div {:class (stl/css :ecommerce-kit-section)}
     [:button {:type           "button"
               :class          (stl/css-case :ecommerce-kit-button true
                                                 :ecommerce-kit-button-busy busy)
               :disabled       ^boolean busy
               :on-click       on-add
               :data-testid    "add-ecommerce-kit"
               :aria-label     (tr "workspace.assets.ecommerce-add-aria")}
      ;; Lucide `shopping-bag` icon (24x24, stroke-width 2, currentColor).
      [:svg {:viewBox "0 0 24 24" :width 18 :height 18 :fill "none"
             :stroke "currentColor" :stroke-width 2
             :stroke-linecap "round" :stroke-linejoin "round"
             :aria-hidden true}
       [:path {:d "M6 2 3 6v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6l-3-4Z"}]
       [:line {:x1 3 :y1 6 :x2 21 :y2 6}]
       [:path {:d "M16 10a4 4 0 0 1-8 0"}]]
      [:span {:class (stl/css :ecommerce-kit-label)}
       (tr "workspace.assets.ecommerce-add")]]]))

;; ── P0.19: Storybook sync (additive) ─────────────────────────────────────────
;;
;; A collapsible section in the Assets panel. A base-URL input + "Sync"
;; button emits `sb/sync-storybook`, which fetches the Storybook stories
;; index via the Rust `storybook_fetch` command, parses it, and registers
;; each story as a code-component entry via the P0.14 host — all in ONE
;; undo transaction. Purely additive — files that have never been synced are
;; byte-identical. The last-used base URL is remembered in localStorage
;; (`ovion.storybook-url`) so a re-sync is one click.
;;
;; Lucide `book-open` icon, inline SVG (stroke-width 2, currentColor), coral
;; accent #f28b82. Reduced-motion: no size/scale transitions — only calm
;; box-shadow / color swaps. A busy state is shown while the sync is in
;; flight; the `sync-storybook` event emits an info/error toast with the
;; real outcome (the busy spinner is "sync initiated" — the toast is
;; "sync finished", mirroring the fire-and-forget idiom of
;; `material-kit-section*` / `ecommerce-kit-section*`).
(mf/defc storybook-section*
  {::mf/private true}
  []
  (let [open*  (mf/use-state false)
        open?  (deref open*)
        url*   (mf/use-state (fn [] (sb/load-storybook-url)))
        url    (deref url*)
        busy*  (mf/use-state false)
        busy   (deref busy*)
        toggle-open (mf/use-fn #(swap! open* not))
        on-url (mf/use-fn (fn [e] (reset! url* (.. e -target -value))))
        on-sync
        (mf/use-fn
         (mf/deps [busy url])
         (fn [e]
           (dom/prevent-default e)
           (let [u (str/trim url)]
             (when (and (not ^boolean busy) (not (str/blank? u)))
               (reset! busy* true)
               (sb/save-storybook-url u)
               (st/emit! (sb/sync-storybook u))
               ;; The event is fire-and-forget (detached promise + toast).
               ;; Reset the busy spinner after a short grace period so the
               ;; button recovers; the real result arrives via the event's
               ;; `ntf/info` / `ntf/error` toast. Mirrors m3-kit-section*.
               (js/setTimeout #(reset! busy* false) 1500)))))]
    [:div {:class (stl/css :storybook-section)}
     [:> title-bar* {:collapsable  true
                     :collapsed    (not open?)
                     :on-collapsed toggle-open
                     :title        (tr "workspace.assets.storybook")
                     :class        (stl/css :title-bar)}]
     (when open?
       [:div {:class (stl/css :storybook-content)}
        [:form {:class (stl/css :storybook-sync)
                :on-submit on-sync}
         [:input {:type        "text"
                  :class       (stl/css :storybook-input)
                  :value       url
                  :placeholder (tr "workspace.assets.storybook-url-placeholder")
                  :on-change   on-url
                  :aria-label  (tr "workspace.assets.storybook-url-aria")}]
         [:button {:type     "submit"
                   :class    (stl/css-case :storybook-sync-btn true
                                           :storybook-sync-btn-busy busy)
                   :disabled (or ^boolean busy (str/blank? (str/trim url)))
                   :data-testid "sync-storybook"
                   :aria-label (tr "workspace.assets.storybook-sync-aria")}
          ;; Lucide `book-open` icon (24x24, stroke-width 2, currentColor).
          [:svg {:viewBox "0 0 24 24" :width 16 :height 16 :fill "none"
                 :stroke "currentColor" :stroke-width 2
                 :stroke-linecap "round" :stroke-linejoin "round"
                 :aria-hidden true}
           [:path {:d "M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z"}]
           [:path {:d "M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z"}]]
          [:span {:class (stl/css :storybook-sync-label)}
           (tr "workspace.assets.storybook-sync-btn")]]]
        (when ^boolean busy
          [:div {:class (stl/css :storybook-status)}
           (tr "workspace.assets.storybook-syncing")])])]))

(mf/defc assets-toolbox*
  {::mf/wrap [mf/memo]}
  [{:keys [size file-id]}]
  (let [read-only?     (mf/use-ctx ctx/workspace-read-only?)
        filters*       (mf/use-state
                        (fn []
                          (-> (or (get @session-filters* file-id)
                                  {:term ""
                                   :section "all"})
                              (assoc :ordering (dwa/get-current-assets-ordering)
                                     :list-style (dwa/get-current-assets-list-style)
                                     :open-menu false))))
        filters        (deref filters*)
        term           (:term filters)
        list-style     (:list-style filters)
        menu-open?     (:open-menu filters)
        section        (:section filters)
        ordering       (:ordering filters)
        reverse-sort?  (= :desc ordering)
        libs           (mf/deref refs/libraries)
        num-libs       (count libs)
        file           (get libs file-id)
        shared?        (:is-shared file)
        components     (mf/with-memo [file] (ctkl/components (:data file)))

        toggle-ordering
        (mf/use-fn
         (mf/deps ordering)
         (fn []
           (let [new-value (toggle-values ordering :asc :desc)]
             (swap! filters* assoc :ordering new-value)
             (dwa/set-current-assets-ordering! new-value))))

        toggle-list-style
        (mf/use-fn
         (mf/deps list-style)
         (fn []
           (let [new-value (toggle-values list-style :thumbs :list)]
             (swap! filters* assoc :list-style new-value)
             (dwa/set-current-assets-list-style! new-value))))

        on-search-term-change
        (mf/use-fn
         (fn [event]
           (st/emit! (dw/clear-assets-section-open))
           (swap! filters* assoc :term event)))

        on-section-filter-change
        (mf/use-fn
         (fn [event]
           (let [value (or (-> (dom/get-target event)
                               (dom/get-value))
                           (as-> (dom/get-current-target event) $
                             (dom/get-attribute $ "data-testid")))]
             (st/emit! (dw/clear-assets-section-open))
             (swap! filters* assoc :section value :open-menu false))))

        show-libraries-dialog
        (mf/use-fn
         (mf/deps file-id)
         (fn []
           (modal/show! :libraries-dialog {:file-id file-id})))

        on-open-menu
        (mf/use-fn  #(swap! filters* update :open-menu not))

        on-menu-close
        (mf/use-fn #(swap! filters* assoc :open-menu false))

        ;; Memoize options to prevent infinite re-render loops when dev-tools are open.
        ;;
        ;; Problem: When dev-tools are open, they constantly monitor the application state,
        ;; triggering frequent updates to okulary refs. This causes the parent component to
        ;; re-render constantly, recreating the options array on every render.
        ;;
        ;; The context-menu* component has a mf/with-effect that depends on [options].
        ;; When options are recreated (even with identical content), the effect runs,
        ;; updating the internal state, which triggers another re-render, creating
        ;; an infinite loop: render -> new options -> effect -> state update -> render...
        options
        (mf/with-memo [on-section-filter-change]
          [{:name    (tr "workspace.assets.box-filter-all")
            :id      "all"
            :handler on-section-filter-change}
           {:name    (tr "workspace.assets.components")
            :id      "components"
            :handler on-section-filter-change}
           {:name    (tr "workspace.assets.colors")
            :id      "colors"
            :handler on-section-filter-change}
           {:name    (tr "workspace.assets.typography")
            :id      "typographies"
            :handler on-section-filter-change}
           ;; Figma-parity reusable styles (gap #32). Optional filter
           ;; entries; the file-library renderer only renders a section
           ;; when the file actually carries the corresponding styles, so
           ;; selecting these on a file without them is a no-op.
           {:name    (tr "workspace.assets.effect-styles")
            :id      "effect-styles"
            :handler on-section-filter-change}
           {:name    (tr "workspace.assets.stroke-styles")
            :id      "stroke-styles"
            :handler on-section-filter-change}
           {:name    (tr "workspace.assets.grid-styles")
            :id      "grid-styles"
            :handler on-section-filter-change}])]

    (mf/with-effect [file-id term section]
      (swap! session-filters* assoc file-id {:term term :section section}))

    [:article  {:class (stl/css :assets-bar)}
     [:div {:class (stl/css :assets-header)}
      (when-not ^boolean read-only?
        (if (and (= num-libs 1) (empty? components) (not shared?))
          [:button {:class (stl/css :add-library-button)
                    :on-click show-libraries-dialog
                    :data-testid "libraries"}
           (tr "workspace.assets.add-library")]

          [:button {:class (stl/css :libraries-button)
                    :on-click show-libraries-dialog
                    :data-testid "libraries"}
           (tr "workspace.assets.manage-library")]))


      [:div {:class (stl/css :search-wrapper)}
       [:> search-bar* {:on-change on-search-term-change
                        :value term
                        :placeholder (tr "workspace.assets.search")}
        [:button
         {:on-click on-open-menu
          :title (tr "workspace.assets.filter")
          :class (stl/css-case :section-button true
                               :opened menu-open?)}
         deprecated-icon/filter-icon]]

       [:> context-menu*
        {:on-close on-menu-close
         :selectable true
         :selected section
         :show menu-open?
         :fixed true
         :min-width true
         :width size
         :top 158
         :left 18
         :options options}]

       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "workspace.assets.sort")
                         :on-click toggle-ordering
                         :icon (if reverse-sort? "asc-sort" "desc-sort")}]]]

     ;; Figma #70: visual + semantic asset search scaffold (additive).
     [:> visual-search* {:file-id file-id}]

     ;; P0.04: built-in stock asset library (Iconify + Pexels), additive.
     [:> stock-section* {:file-id file-id}]

     ;; P2.11: Material 3 design kit injection (additive, idempotent).
     [:> material-kit-section*]

     ;; P1.15: E-commerce design kit injection (additive, idempotent).
     [:> ecommerce-kit-section*]

     ;; P0.19: Storybook sync — register external React component libraries
     ;; from a Storybook index as code-component entries (additive, opt-in).
     [:> storybook-section*]

     [:> (mf/provider cmm/assets-filters) {:value filters}
      [:> (mf/provider cmm/assets-toggle-ordering) {:value toggle-ordering}
       [:> (mf/provider cmm/assets-toggle-list-style) {:value toggle-list-style}
        [:*
         [:> assets-local-library* {:filters filters}]
         [:> assets-libraries* {:filters filters}]]]]]]))
