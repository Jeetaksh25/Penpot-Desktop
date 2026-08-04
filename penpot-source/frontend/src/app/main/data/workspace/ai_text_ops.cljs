;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.ai-text-ops
  "P2.04 — AI Smart Text operations on the selected canvas text shape:
  Translate (layout-preserving), Continue Writing, Polish, Summarize.

  Each op builds a prompt, fires the LLM via the existing Tauri invoke
  path (`ai/invoke-generate` + `ai/extract-text-from-spec`, the same path
  the relocated rename / generate-text tools use), and writes the result
  back to the selected text shape via `dwsh/update-shapes` with
  `:attrs [:content]`.

  Layout is preserved BY CONSTRUCTION:
    - Only :content is updated; :x/:y/:width/:height are never touched,
      so the text box keeps its exact position and dimensions (this is
      what 'layout-preserving translation' means here).
    - `txt/change-text` reuses the first paragraph/text node styles of the
      EXISTING content, so font family, size, weight, align, fills and
      line-height stay byte-identical — only the characters change.

  Reuses the global :ai-busy / :ai-error slots + the gen-id guard from
  `ai_gen.cljs` so the AI bar's existing stage/error line surfaces
  progress and failures with no new UI."
  (:require
   [cuerdas.core :as str]
   [app.common.data.macros :as dm]
   [app.common.types.text :as txt]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.ai-gen :as ai]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.store :as st]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]
   [promesa.core :as p]))

;; ── Selection helpers ────────────────────────────────────────────────────────

(defn selected-text-shape
  "Return the single selected text shape from `state`, or nil when zero,
  multiple, or a non-text shape is selected."
  [state]
  (let [objects  (dsh/lookup-page-objects state)
        selected (dsh/lookup-selected state)]
    (when (= (count selected) 1)
      (let [s (get objects (first selected))]
        (when (= :text (:type s)) s)))))

;; ── Prompt builders ──────────────────────────────────────────────────────────

(defn op-prompt
  "Build the LLM prompt for `op` (a keyword) acting on `text` (the shape's
  current plain text). `lang` is an optional target language string used
  only by :translate. The prompt always asks for ONLY the result text —
  no quotes, no explanation — so `ai/extract-text-from-spec` can recover
  it from the DesignSpec the backend returns."
  [op text lang]
  (let [text (or text "")]
    (case op
      :translate
      (dm/str
       "Translate the following UI text into " (or lang "English") ". "
       "Keep the meaning and tone. Preserve any line breaks (\\n). "
       "Do NOT add quotes, explanations, or notes. "
       "Reply with only the translated text.\n\n"
       "Text:\n" text)

      :continue
      (dm/str
       "Continue writing the following UI text naturally, in the same voice "
       "and style, adding 1 to 3 sentences (or up to one short paragraph). "
       "Do NOT repeat the original text. Do NOT add quotes or explanations. "
       "Reply with only the continuation text (NOT the original + continuation).\n\n"
       "Text:\n" text)

      :polish
      (dm/str
       "Polish the following UI text for clarity, grammar and concision. Keep "
       "the meaning and tone. Preserve line breaks (\\n). Do NOT add quotes or "
       "explanations. Reply with only the polished text.\n\n"
       "Text:\n" text)

      :summarize
      (dm/str
       "Summarize the following UI text into a concise single line suitable "
       "for a UI label. Do NOT add quotes or explanations. Reply with only "
       "the summary.\n\n"
       "Text:\n" text)

      ;; Unknown op — fall through to a no-op prompt.
      "")))

;; ── The event ────────────────────────────────────────────────────────────────

(defn- err->str [err]
  (cond
    (string? err)              err
    (some-> err .-message)     (.-message err)
    :else                      (tr "workspace.ai.bar.error-generic")))

(defn run-text-op
  "WatchEvent. Run an AI text operation on the currently selected text
  shape. `op` is one of :translate :continue :polish :summarize. `lang` is
  an optional target language string (used by :translate; ignored
  otherwise).

  Flow:
    1. read the selected text shape from state (nil → error toast)
    2. build the op prompt from the shape's current text
    3. fire `ai/invoke-generate` with a tool request (same path as rename)
    4. on success: `ai/extract-text-from-spec` → result text →
       `txt/change-text` (preserves styles) → `dwsh/update-shapes` with
       `:attrs [:content]` (preserves geometry)
    5. on error: surface via :ai-error

  Uses the gen-id guard so a cancel / newer generation drops a late
  result, exactly like `generate-design`."
  [{:keys [op lang]}]
  (ptk/reify ::run-text-op
    ptk/WatchEvent
    (watch [_ state _]
      (let [shape (selected-text-shape state)]
        (if-not shape
          (rx/of (ai/set-ai-error (tr "workspace.ai.bar.text-op-select-text")))
          (let [id         (:id shape)
                content    (:content shape)
                current    (if content (txt/content->text content) "")
                prompt     (op-prompt op current lang)
                my-id      (ai/bump-gen-id)
                request    (ai/build-tool-request prompt)
                apply-result
                (fn [result-text]
                  (when (and (string? result-text)
                             (not (str/empty? result-text)))
                    ;; Preserve styles + geometry: only :content changes.
                    ;; `txt/change-text` borrows the first paragraph/text
                    ;; node styles from the existing content so the new
                    ;; text inherits font/size/weight/align/fills exactly.
                    (let [new-content (txt/change-text content result-text)]
                      (st/emit!
                       (dwsh/update-shapes
                        [id]
                        (fn [s] (assoc s :content new-content))
                        {:attrs [:content]})))))
                handle
                (fn [result-js]
                  (when (= my-id (ai/gen-id-current))
                    (let [copy (ai/extract-text-from-spec result-js)]
                      (if (and (string? copy) (not (str/empty? copy)))
                        (do (apply-result copy)
                            (st/emit! (ai/set-ai-busy false)
                                      (ai/set-ai-error nil)))
                        (st/emit! (ai/set-ai-busy false)
                                  (ai/set-ai-error
                                   (tr "workspace.ai.bar.tool-no-result")))))))
                handle-err
                (fn [err]
                  (when (= my-id (ai/gen-id-current))
                    (st/emit! (ai/set-ai-busy false)
                              (ai/set-ai-error (err->str err)))))]
            ;; Detached promise — fires side-effects via st/emit! on resolve.
            (-> (ai/invoke-generate request)
                (p/then handle)
                (p/catch handle-err))
            ;; Mark busy immediately so the bar shows its spinner.
            (rx/of (ai/set-ai-busy true))))))))