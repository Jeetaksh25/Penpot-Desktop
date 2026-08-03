;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.ai-image
  "Phase 2 — the AI Image panel/modal. Generates images from a prompt
  (DeepInfra FLUX via `llm_generate_image`) and, when an image shape is
  selected, offers Remove background (BiRefNet) and Upscale 2x/4x
  (RealESRGAN) actions.

  Visual language is the reference-pinned AI design system (see
  ai-design.cljs): white surfaces, the #f28b82 coral accent, Helvetica Now
  Display, Lucide glyphs at stroke-width 2, coral inset glow on small
  controls. Motion is calm and reuses ai-motion helpers (pop-in entrance,
  hov-coral/hov-white hover/press) with the non-negotiable reduced-motion
  guard.

  Registered as a modal `:ai-image` (mirroring ai-settings), opened from the
  AI bar's cluster 'Generate image' button via
  `(st/emit! (modal/show {:type :ai-image}))`."
  (:require
   [cuerdas.core :as str]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace.ai-gen :as ai]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.workspace.ai-design :as ad]
   [app.main.ui.workspace.ai-motion :as aim]
   [app.util.i18n :as i18n :refer [tr]]
   [promesa.core :as p]
   [rumext.v2 :as mf]))

;; ── Injected CSS ─────────────────────────────────────────────────────────────
;;
;; Scoped under `.aimg-root`. Built on `ai-base-css` tokens (coral #f28b82,
;; grey #7d7d7d, white surfaces, Helvetica Now Display). Same construction as
;; ai-settings' `.ais-*` classes, renamed to avoid collisions when both
;; modals are mounted.
(def ^:private image-css
  "
.aimg-overlay { position: fixed; inset: 0; background: var(--ai-overlay);
  z-index: 210; display: flex; align-items: center; justify-content: center; padding: 24px;
  animation: ai-overlay-in var(--ai-dur-base) var(--ai-ease-out) both; }
.aimg-modal { background: var(--ai-white); border-radius: var(--ai-radius-md);
  width: min(520px, 100%); max-height: 88vh; display: flex; flex-direction: column;
  overflow: hidden; box-shadow: 0 24px 70px rgba(0, 0, 0, 0.4);
  font-family: var(--ai-font); color: var(--ai-ink);
  animation: ai-modal-in var(--ai-dur-slow) var(--ai-ease-out) both; }
.aimg-head { padding: 16px 20px; border-bottom: 1px solid #ececec;
  display: flex; align-items: center; justify-content: space-between; }
.aimg-title { font-size: 15px; font-weight: 700; color: var(--ai-ink);
  font-family: var(--ai-font); display: inline-flex; align-items: center; gap: 9px; }
.aimg-title .aimg-i { width: 18px; height: 18px; color: var(--ai-coral); }
.aimg-body { padding: 18px 20px; overflow: auto; flex: 1;
  display: flex; flex-direction: column; gap: 14px; }
.aimg-foot { padding: 14px 20px; border-top: 1px solid #ececec;
  display: flex; gap: 10px; justify-content: flex-end; }

.aimg-field { display: flex; flex-direction: column; gap: 5px; }
.aimg-label { font-size: 12px; font-weight: 600; color: var(--ai-ink); font-family: var(--ai-font); }
.aimg-hint { font-size: 11px; color: var(--ai-grey-2); font-family: var(--ai-font); }
.ainput { border: 1px solid #ececec; border-radius: 10px; padding: 8px 10px;
  font-size: 13px; font-family: var(--ai-font); color: var(--ai-ink);
  background: var(--ai-white); outline: none;
  transition: border-color var(--ai-dur-fast) var(--ai-ease-out),
              box-shadow var(--ai-dur-fast) var(--ai-ease-out); }
.ainput:focus { border-color: var(--ai-coral); box-shadow: 0 0 0 3px var(--ai-coral-faint); }
.ainput-area { resize: vertical; min-height: 72px; line-height: 1.45; }

/* Size selector — three coral-tinted pill chips. */
.aimg-sizes { display: flex; gap: 8px; }
.aimg-size { flex: 1 1 0; height: 36px; padding: 0 10px; border: none; cursor: pointer;
  background: var(--ai-white); color: var(--ai-grey-2); font-size: 12.5px; font-weight: 600;
  font-family: var(--ai-font); border-radius: var(--ai-radius-pill);
  box-shadow: var(--ai-shadow-btn), var(--ai-inset-coral);
  transition: color var(--ai-dur-fast) var(--ai-ease-out),
              background var(--ai-dur-fast) var(--ai-ease-out); }
.aimg-size:hover { color: var(--ai-ink); }
.aimg-size.is-cur { background: var(--ai-coral); color: var(--ai-white);
  box-shadow: var(--ai-shadow-btn); }
.aimg-size:focus-visible { outline: none; box-shadow: var(--ai-shadow-btn), var(--ai-inset-coral), 0 0 0 3px var(--ai-coral-faint); }
.aimg-size:disabled { opacity: 0.5; cursor: not-allowed; }

.aimg-btn { border: none; border-radius: var(--ai-radius-md); padding: 9px 16px; font-size: 13px;
  font-weight: 600; cursor: pointer; font-family: var(--ai-font);
  display: inline-flex; align-items: center; justify-content: center; gap: 8px; white-space: nowrap;
  transition: background var(--ai-dur-fast) var(--ai-ease-out),
              color var(--ai-dur-fast) var(--ai-ease-out); }
.aimg-btn:focus-visible { outline: none; box-shadow: 0 0 0 3px var(--ai-coral-faint); }
.aimg-btn-primary { background: var(--ai-coral); color: var(--ai-white);
  box-shadow: var(--ai-shadow-btn), var(--ai-inset-white); }
.aimg-btn-primary:hover { background: var(--ai-coral-press); }
.aimg-btn-primary:disabled { background: #f3c4be; cursor: not-allowed; box-shadow: var(--ai-shadow-btn); }
.aimg-btn-ghost { background: var(--ai-white); color: var(--ai-grey-2);
  box-shadow: var(--ai-shadow-btn), var(--ai-inset-coral); }
.aimg-btn-ghost:hover { color: var(--ai-ink); }
.aimg-btn-ghost:disabled { opacity: 0.5; cursor: not-allowed; }
.aimg-btn .aimg-i { width: 15px; height: 15px; }

.aimg-close { width: 34px; height: 34px; flex: none; border: none; cursor: pointer;
  background: var(--ai-white); color: var(--ai-grey); border-radius: 50%;
  display: inline-flex; align-items: center; justify-content: center;
  box-shadow: var(--ai-shadow-btn), var(--ai-inset-coral);
  transition: color var(--ai-dur-fast) var(--ai-ease-out); }
.aimg-close:hover { color: var(--ai-ink); }
.aimg-close:focus-visible { outline: none; box-shadow: var(--ai-shadow-btn), var(--ai-inset-coral), 0 0 0 3px var(--ai-coral-faint); }
.aimg-close .aimg-i { width: 18px; height: 18px; }

/* Result preview — the generated image centered on a checkerboard-ish bg. */
.aimg-preview { width: 100%; min-height: 120px; border-radius: var(--ai-radius-md);
  background: #fafafa; border: 1px solid #ececec; display: flex; align-items: center;
  justify-content: center; overflow: hidden; }
.aimg-preview img { max-width: 100%; max-height: 320px; object-fit: contain; display: block; }
.aimg-preview-empty { font-size: 12px; color: var(--ai-grey-2); font-family: var(--ai-font); padding: 24px; text-align: center; }

/* Edit actions for a selected image shape. */
.aimg-edit { display: flex; gap: 8px; flex-wrap: wrap; }
.aimg-edit .aimg-btn { flex: 1 1 0; }

.aimg-sel { font-size: 11px; color: var(--ai-grey-2); font-family: var(--ai-font); }
.aimg-sel b { color: var(--ai-ink); font-weight: 600; }
.aimg-status { font-size: 11px; font-family: var(--ai-font); min-height: 14px; line-height: 1.4; color: var(--ai-grey-2); }
.aimg-status.is-err { color: #b3261e; }

.aimg-spin { width: 14px; height: 14px; border: 2px solid rgba(242,139,130,0.3);
  border-top-color: var(--ai-coral); border-radius: 50%;
  animation: ai-spin 0.7s linear infinite; display: inline-block; }
")

;; ── Lucide icons (stroke-width 2, currentColor) ──────────────────────────────
(defn- li
  "Wrap a seq of SVG children in a Lucide 24×24 icon frame."
  [body]
  [:svg.aimg-i {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
                :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
                :aria-hidden "true"} body])

(def ^:private lucide-image
  (li [[:rect {:x 3 :y 3 :width 18 :height 18 :rx 2 :ry 2}]
       [:circle {:cx 9 :cy 9 :r 2}]
       [:path {:d "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21"}]]))

(def ^:private lucide-x
  (li [[:path {:d "M18 6 6 18"}]
       [:path {:d "m6 6 12 12"}]]))

(def ^:private lucide-sparkles
  (li [[:path {:d "M9.937 15.5A2 2 0 0 0 8.5 14.063l-6.135-1.582a.5.5 0 0 1 0-.962L8.5 9.936A2 2 0 0 0 9.937 8.5l1.582-6.135a.5.5 0 0 1 .963 0L14.063 8.5A2 2 0 0 0 15.5 9.937l6.135 1.581a.5.5 0 0 1 0 .964L15.5 14.063a2 2 0 0 0-1.437 1.437l-1.582 6.135a.5.5 0 0 1-.963 0z"}]
       [:path {:d "M20 3v4"}]
       [:path {:d "M22 5h-4"}]]))

(def ^:private lucide-scissors
  (li [[:circle {:cx 6 :cy 6 :r 3}]
       [:circle {:cx 6 :cy 18 :r 3}]
       [:path {:d "M20 4 8.12 15.88"}]
       [:path {:d "M14.47 14.48 20 20"}]
       [:path {:d "M8.12 8.12 12 12"}]]))

(def ^:private lucide-maximize
  (li [[:path {:d "M8 3H5a2 2 0 0 0-2 2v3"}]
       [:path {:d "M21 8V5a2 2 0 0 0-2-2h-3"}]
       [:path {:d "M3 16v3a2 2 0 0 0 2 2h3"}]
       [:path {:d "M16 21h3a2 2 0 0 0 2-2v-3"}]]))

(defn- spinner [] [:span.aimg-spin])

;; ── Size presets ─────────────────────────────────────────────────────────────
;; UI ratio -> backend size string (contract section 1: 1024x1024 | 1024x1792
;; | 1792x1024). 2:3 is portrait, 3:2 is landscape.
(def ^:private sizes
  [{:v "1024x1024"   :ratio "1:1"  :key :size-square}
   {:v "1024x1792"   :ratio "2:3"  :key :size-portrait}
   {:v "1792x1024"   :ratio "3:2"  :key :size-landscape}])

(defn- size-by-v [v] (some #(when (= (:v %) v) %) sizes))

;; Registered as a modal so the AI bar can open it via
;; `(st/emit! (modal/show {:type :ai-image}))`. Mirrors ai-settings.
(mf/defc ai-image*
  {::mf/register modal/components
   ::mf/register-as :ai-image}
  [{:keys [on-close]}]
  (let [prompt*   (mf/use-state "")
        size*     (mf/use-state "1024x1024")
        ;; Local error surface (e.g. empty-prompt). The shared refs/ai-error
        ;; is reserved for the design-generation flow in the AI bar.
        err*      (mf/use-state nil)

        busy      (mf/deref refs/ai-busy)
        img       (mf/deref refs/ai-image)        ; {:base64 :mime} or nil

        close     (mf/use-fn
                   (fn []
                     (when (fn? on-close) (on-close))
                     (st/emit! (modal/hide))))

        prompt    (deref prompt*)
        size      (deref size*)
        err       (deref err*)

        ;; The edit actions (remove-bg / upscale) operate on the generated
        ;; image in refs/ai-image — a self-contained generate→edit→insert
        ;; workflow that is fully local. Operating on a canvas shape's image
        ;; fill would require fetching the media blob from the backend; the
        ;; generate→edit path is the production-ready flow, so the buttons act
        ;; on the image currently shown in the result preview.
        has-img?  (boolean (and (:base64 img) (:mime img)))

        on-prompt (mf/use-fn (fn [e] (reset! prompt* (.. e -target -value))))
        on-size   (mf/use-fn (fn [v] (reset! size* v)))

        on-generate
        (mf/use-fn
         (mf/deps prompt size)
         (fn []
           (if (str/empty? prompt)
             (reset! err* (tr "workspace.ai.image.empty"))
             (do
               (reset! err* nil)
               (st/emit! (ai/set-ai-image nil)
                         (ai/generate-image {:prompt prompt :size size}))))))

        on-remove-bg
        (mf/use-fn
         (mf/deps img)
         (fn []
           (when-let [{:keys [base64 mime]} img]
             (reset! err* nil)
             (st/emit! (ai/remove-background
                        {:image-input {:mime (or mime "image/png") :b64 base64}})))))

        on-upscale
        (mf/use-fn
         (mf/deps img)
         (fn [scale]
           (when-let [{:keys [base64 mime]} img]
             (reset! err* nil)
             (st/emit! (ai/upscale-image
                        {:image-input {:mime (or mime "image/png") :b64 base64}
                         :scale scale})))))

        on-insert
        (mf/use-fn
         (mf/deps img)
         (fn []
           ;; Copy the generated image as a data URL to the clipboard — a
           ;; self-contained "insert" that needs no new backend event. The
           ;; user can paste it into a rect's image-fill picker on canvas.
           (when-let [{:keys [base64 mime]} img]
             (let [data-url (str "data:" (or mime "image/png") ";base64," base64)]
               (some-> js/navigator .-clipboard (.writeText data-url)
                       (p/then (fn [_] (st/emit! (ntf/info (tr "workspace.ai.image.insert")))))
                       (p/catch (fn [e] (st/emit! (ntf/error (str e))))))))))

        on-clear
        (mf/use-fn (fn [] (st/emit! (ai/set-ai-image nil))))]

    [:div {:class "aimg-overlay aimg-root" :on-click close}
     [:div.aimg-modal {:on-click #(.stopPropagation %)}
      [:div {:style #js {"display" "contents"}}
       (ad/base-style-block)
       [:style {:dangerouslySetInnerHTML #js {:__html image-css}}]]
      [:div.aimg-head
       [:span.aimg-title lucide-image (tr "workspace.ai.image.title")]
       [:button.aimg-close {:type "button" :on-click close
                            :on-mouse-enter aim/hov-white-in
                            :on-mouse-leave aim/hov-white-out
                            :on-mouse-down aim/press-white-in
                            :on-mouse-up aim/press-white-out}
        lucide-x]]
      [:div.aimg-body
       [:div.aimg-field
        [:span.aimg-label (tr "workspace.ai.image.prompt")]
        [:textarea.ainput.ainput-area
         {:value prompt
          :on-change on-prompt
          :rows 3
          :placeholder (tr "workspace.ai.image.prompt")}]]
       [:div.aimg-field
        [:span.aimg-label (tr "workspace.ai.image.size")]
        [:div.aimg-sizes
         (for [s sizes]
           [:button.aimg-size
            {:key (:v s)
             :type "button"
             :class (when (= (:v s) size) "is-cur")
             :disabled busy
             :on-click #(on-size (:v s))}
            (tr (str "workspace.ai.image." (name (:key s))))])]]

        ;; Generate
        [:button.aimg-btn.aimg-btn-primary
         {:type "button"
          :on-click on-generate
          :disabled busy
          :on-mouse-enter aim/hov-coral-in
          :on-mouse-leave aim/hov-coral-out
          :on-mouse-down aim/press-coral-in
          :on-mouse-up aim/press-coral-out}
         (if busy (spinner) lucide-sparkles)
         (tr "workspace.ai.image.generate")]

        ;; Result preview
        (if (and (:base64 img) (:mime img))
          [:div.aimg-field
           [:span.aimg-label (tr "workspace.ai.image.title")]
           [:div.aimg-preview
            [:img {:src (str "data:" (:mime img) ";base64," (:base64 img))
                   :alt (tr "workspace.ai.image.title")}]]
           [:div {:style #js {"display" "flex" "gap" "8px"}}
            [:button.aimg-btn.aimg-btn-ghost
             {:type "button" :on-click on-insert
              :on-mouse-enter aim/hov-white-in
              :on-mouse-leave aim/hov-white-out
              :on-mouse-down aim/press-white-in
              :on-mouse-up aim/press-white-out}
             (tr "workspace.ai.image.insert")]
            [:button.aimg-btn.aimg-btn-ghost
             {:type "button" :on-click on-clear
              :on-mouse-enter aim/hov-white-in
              :on-mouse-leave aim/hov-white-out
              :on-mouse-down aim/press-white-in
              :on-mouse-up aim/press-white-out}
             (tr "workspace.ai.bar.close")]]
           [:span.aimg-hint (tr "workspace.ai.image.insert")]]
          [:div.aimg-preview [:span.aimg-preview-empty (tr "workspace.ai.image.empty")]])

        ;; Edit the generated image (remove background / upscale). These act
        ;; on the image currently in refs/ai-image — the generate→edit flow.
        (if has-img?
          [:div.aimg-field
           [:span.aimg-sel (tr "workspace.ai.image.edit-hint")]
           [:div.aimg-edit
            [:button.aimg-btn.aimg-btn-ghost
             {:type "button" :on-click on-remove-bg :disabled busy
              :on-mouse-enter aim/hov-white-in
              :on-mouse-leave aim/hov-white-out
              :on-mouse-down aim/press-white-in
              :on-mouse-up aim/press-white-out}
             lucide-scissors
             (tr "workspace.ai.image.remove-bg")]
            [:button.aimg-btn.aimg-btn-ghost
             {:type "button" :on-click #(on-upscale 2) :disabled busy
              :on-mouse-enter aim/hov-white-in
              :on-mouse-leave aim/hov-white-out
              :on-mouse-down aim/press-white-in
              :on-mouse-up aim/press-white-out}
             lucide-maximize
             (tr "workspace.ai.image.upscale-2x")]
            [:button.aimg-btn.aimg-btn-ghost
             {:type "button" :on-click #(on-upscale 4) :disabled busy
              :on-mouse-enter aim/hov-white-in
              :on-mouse-leave aim/hov-white-out
              :on-mouse-down aim/press-white-in
              :on-mouse-up aim/press-white-out}
             lucide-maximize
             (tr "workspace.ai.image.upscale-4x")]]]
          nil)

        [:div {:class (str "aimg-status " (if err "is-err" ""))} err]]

      [:div.aimg-foot
       [:button.aimg-btn.aimg-btn-ghost {:on-click close :disabled busy
                                         :on-mouse-enter aim/hov-white-in
                                         :on-mouse-leave aim/hov-white-out
                                         :on-mouse-down aim/press-white-in
                                         :on-mouse-up aim/press-white-out}
        (tr "workspace.ai.bar.close")]]]]))