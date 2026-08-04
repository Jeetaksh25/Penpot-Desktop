;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.prompt-library
  "AI Prompt Library (ALL_APPS_PARITY P2.03).

  A grouped set of example design prompts the user can pick from the AI
  bar to fill the prompt input, plus user-saved presets persisted on
  FILE-DATA plugin-data (namespace `:ovion`, key `\"prompt-library\"`),
  so they are undo/redo-safe and survive save/reload.

  Why plugin-data and not a new file-data key:
    * process-change for :set-plugin-data is the sanctioned generic
      file-level extension point (object-type :file); it is already
      undo/redo-safe and namespace-isolated, requiring zero shared-file
      edits (mirrors data/workspace/ds_versions.cljs at the file level).

  Storage location:
    file-data :plugin-data :ovion \"prompt-library\"  -> EDN string of the preset vector.

  Preset shape:
    {:group <string> :label <string> :prompt <string>}

  The default library is a constant (not persisted): it ships with the
  app and is always merged into the picker alongside the user presets.
  Only user presets (add / delete) are persisted; `use-preset` is a pure
  data fn that just returns the chosen prompt string to the caller (no
  state change), so the AI bar can fill its local input atom without a
  round-trip through the store."
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [cljs.reader :as reader]
   [potok.v2.core :as ptk]))

;; --- Plugin-data slot constants --------------------------------------------

(def prompt-library-namespace
  "Plugin-data namespace keyword under which user prompt presets are
  stored on the file. Schema:plugin-data keys namespaces are keywords."
  :ovion)

(def prompt-library-key
  "Plugin-data key (string) under `prompt-library-namespace` for the
  user preset vector."
  "prompt-library")

;; --- Default library (always present, never persisted) ---------------------

(defn- default-presets
  "A fresh vector of the built-in example prompt groups. Kept as a
  function (not a constant) so callers never mutate a shared reference.
  Each entry is {:group :label :prompt}. Groups are stable so the picker
  can cluster presets by group for both defaults and user additions."
  []
  [{:group "Layout"    :label "Two-column hero"
    :prompt "Create a two-column hero section: a left-aligned headline with a short subhead and a primary call-to-action button, and a right column with a large image placeholder. Use a 12-column grid with generous whitespace."}
   {:group "Layout"    :label "Centered card grid"
    :prompt "Design a responsive 3-column card grid with equal-width cards, each containing an icon, a title, two lines of body copy, and a text link. Add consistent gaps and align all cards to a shared baseline."}
   {:group "Layout"    :label "Sticky header + footer"
    :prompt "Build a page with a sticky top navigation bar (logo left, links right) and a fixed footer with three columns of links. The main content area should scroll between them."}

   {:group "Components" :label "Primary button variants"
    :prompt "Create a primary button component with three variants: default, hover, and disabled. Use the coral accent color, 40px height, pill radius, and a white arrow-up icon on the right."}
   {:group "Components" :label "Form input field"
    :prompt "Design a text input component with a label above, a placeholder, a 1px coral focus ring, and an error state with a red border and helper text below. Make it reusable as a main component."}
   {:group "Components" :label "Modal dialog"
    :prompt "Build a centered modal dialog component with an overlay, a header (title + close button), a body slot, and a footer with a ghost Cancel button and a coral primary button."}

   {:group "Color"     :label "Warm coral palette"
    :prompt "Set up a warm color palette: coral #f28b82 as the primary accent, a soft coral tint for hover backgrounds, a neutral grey #7d7d7d for secondary text, and an off-white surface. Apply it to the selected frame."}
   {:group "Color"     :label "Dark theme swap"
    :prompt "Convert the selected frame to a dark theme: near-black background, off-white text, and the coral accent kept for primary actions. Preserve the existing layout and spacing."}
   {:group "Color"     :label "Accessible contrast pass"
    :prompt "Audit the selected screen for color contrast and adjust text colors so body copy meets WCAG AA against its background. Keep the visual hierarchy and accent color unchanged."}

   {:group "Content"   :label "Realistic marketing copy"
    :prompt "Replace the placeholder text in the selection with realistic, concise marketing copy for a productivity SaaS landing page. Keep each text block under 12 words where possible."}
   {:group "Content"   :label "Lorem ipsum fill"
    :prompt "Fill every empty text shape on the current page with one line of lorem ipsum so the layout reads as a populated mockup. Vary the length slightly between blocks."}
   {:group "Content"   :label "Icon labels"
    :prompt "Suggest short, clear labels (one or two words) for each icon in the selection and add them as adjacent text shapes. Use sentence case."}

   {:group "Wireframe" :label "Low-fidelity homepage"
    :prompt "Generate a low-fidelity wireframe of a homepage: nav bar, hero with headline and CTA, a 3-feature row, a testimonial quote, and a footer. Use grey placeholders, no images, no color."}
   {:group "Wireframe" :label "Mobile onboarding flow"
    :prompt "Design three mobile screens for an onboarding flow: a welcome screen, a permissions request screen, and a success screen. Use a single column, large tap targets, and minimal copy."}
   {:group "Wireframe" :label "Dashboard skeleton"
    :prompt "Create a low-fidelity dashboard layout: a sidebar nav, a top bar with search and avatar, a KPI row of four metric cards, a chart placeholder, and a recent-activity list."}

   {:group "Polish"    :label "Tighten spacing & alignment"
    :prompt "Review the selected frame and tighten the spacing and alignment: snap gaps to an 8px grid, align headings to a shared left edge, and equalize vertical rhythm between sections."}
   {:group "Polish"    :label "Consistent corner radii"
    :prompt "Audit the selection for inconsistent corner radii and normalize them: 4px for inputs, 8px for cards, 999px for pills and buttons. Keep the layout otherwise unchanged."}
   {:group "Polish"    :label "Shadow & elevation pass"
    :prompt "Add a calm elevation system to the selection: a soft outer shadow on cards, a slightly deeper shadow on hover-only elements, and no shadow on flat surfaces. Keep it subtle."}])

(defn default-groups
  "Return the ordered vector of distinct group names from the default
  library, in display order. Exposed so the picker can render default
  groups in a stable sequence before user-added groups."
  []
  (let [seen (volatile! #{})
        acc  (volatile! [])]
    (doseq [p (default-presets)
            :let [g (:group p)]
            :when (not (contains? @seen g))]
      (vswap! seen conj g)
      (vswap! acc conj g))
    @acc))

;; --- Read helpers -----------------------------------------------------------

(defn read-user-presets
  "Parse the file-data prompt-library slot back into a vector of preset
  maps. Returns [] when the slot is absent / unparsable. The `file-data`
  arg is the file-data map (NOT the whole state)."
  [file-data]
  (let [raw (dm/get-in file-data [:plugin-data prompt-library-namespace prompt-library-key])]
    (if (or (nil? raw) (empty? raw))
      []
      (try
        (let [v (reader/read-string raw)]
          (if (vector? v) v []))
        (catch :default _
          [])))))

(defn all-presets
  "Return the merged vector of default presets followed by user presets
  for `file-data`. Used by the picker to render a single list grouped
  by :group."
  [file-data]
  (into (default-presets) (read-user-presets file-data)))

(defn use-preset
  "Pure data fn: return the prompt string of `preset`. The AI bar fills
  its local input atom with this; no store/state change happens here."
  [preset]
  (:prompt preset))

;; --- Commit helper ----------------------------------------------------------

(defn- commit-user-presets
  "Build and commit a changeset that writes `new-presets` to the file's
  prompt-library slot, inside one undo transaction. Returns an rx stream
  of potok events. Mirrors ds_versions.cljs's `commit-ds-versions` at
  the file level (object-type :file, the 3-arg `set-plugin-data`)."
  [it state new-presets]
  (let [file-id    (:current-file-id state)
        file-data (dsh/lookup-file-data state file-id)]
    (if (nil? file-data)
      (rx/empty)
      (let [undo-id (js/Symbol)
            value   (if (empty? new-presets) nil (pr-str new-presets))
            changes (-> (pcb/empty-changes it)
                        (pcb/with-file-data file-data)
                        (pcb/set-plugin-data prompt-library-namespace
                                              prompt-library-key
                                              value))]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes changes)
               (dwu/commit-undo-transaction undo-id))))))

;; --- Events ----------------------------------------------------------------

(defn add-preset
  "Append a user preset `{:group :label :prompt}` to the file's
  prompt-library slot. Commits via one undo transaction. Returns an rx
  stream. Blank group/label/prompt are coerced to safe defaults so the
  picker never renders an empty-headed entry."
  [{:keys [group label prompt]}]
  (ptk/reify ::add-preset
    ptk/WatchEvent
    (watch [it state _]
      (let [file-id    (:current-file-id state)
            file-data (dsh/lookup-file-data state file-id)
            existing  (read-user-presets file-data)
            group     (if (or (nil? group) (str/blank? group)) "My presets" group)
            label     (if (or (nil? label) (str/blank? label)) "Untitled" label)
            prompt    (if (or (nil? prompt) (str/blank? prompt)) "" prompt)
            preset    {:group group :label label :prompt prompt}
            new-vec   (conj existing preset)]
        (commit-user-presets it state new-vec)))))

(defn delete-preset
  "Remove the user preset at `index` (0-based, within the user-preset
  vector only — not the merged default+user list) from the file's
  prompt-library slot. No-op when the index is out of range. Commits via
  one undo transaction. Returns an rx stream."
  [{:keys [index]}]
  (ptk/reify ::delete-preset
    ptk/WatchEvent
    (watch [it state _]
      (let [file-id    (:current-file-id state)
            file-data (dsh/lookup-file-data state file-id)
            existing  (read-user-presets file-data)
            new-vec   (if (and (int? index)
                               (<= 0 index (dec (count existing))))
                        (into [] (keep-indexed #(when (not= %1 index) %2) existing))
                        existing)]
        (when (not= (count existing) (count new-vec))
          (commit-user-presets it state new-vec))))))