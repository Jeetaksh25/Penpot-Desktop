;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.workshop
  "Workshop — interactive learning center (ALL_APPS_PARITY P1.35).

  A curated catalog of guided, step-by-step tutorials that teach Ovion's
  design workflow (frames, auto-layout, components, prototyping, AI,
  export). The workshop is an opt-in overlay toggled from a header entry
  button; when closed the workspace renders exactly as today
  (byte-identical-when-inactive).

  State model (app-level, NOT file plugin-data — tutorial progress is a
  USER concern, not per-file):
    :workshop-open?            boolean — overlay visibility.
    :workshop-active-tutorial  keyword id of the tutorial currently being
                               run, or nil (library view).
    :workshop-step             current step index (0-based).

  Progress persistence:
    localStorage key  `ovion.workshop.progress`
    value             pr-str `{<tutorial-id> {:step <n> :completed? <bool>}}`
    Read/written via js/window.localStorage from the UI and from events
    that advance/complete/reset a tutorial."
  (:require
   [cljs.reader :as reader]
   [potok.v2.core :as ptk]))

;; --- localStorage key --------------------------------------------------------

(def ^:private progress-key "ovion.workshop.progress")

;; --- Progress helpers --------------------------------------------------------

(defn read-progress
  "Read the persisted progress map from localStorage. Returns a map of
  `{<tutorial-id> {:step <n> :completed? <bool>}}` (keywords for ids) or
  `{}` when nothing is stored / unreadable."
  []
  (try
    (let [raw (.getItem js/window.localStorage progress-key)]
      (if (some? raw)
        (let [parsed (reader/read-string raw)]
          (if (map? parsed) parsed {}))
        {}))
    (catch :default _ {})))

(defn- write-progress
  "Persist the whole progress map to localStorage."
  [progress]
  (try
    (.setItem js/window.localStorage progress-key (pr-str progress))
    (catch :default _ nil)))

(defn- persist-tutorial!
  "Merge one tutorial's progress into localStorage."
  [id step completed?]
  (let [progress (read-progress)
        updated  (assoc progress id {:step step :completed? completed?})]
    (write-progress updated)))

(defn- clear-tutorial-progress!
  "Remove one tutorial's progress (or all when `id` is nil/::all)."
  [id]
  (let [progress (read-progress)
        updated  (if (or (nil? id) (= ::all id))
                   {}
                   (dissoc progress id))]
    (write-progress updated)))

;; --- Tutorial catalog --------------------------------------------------------

(def tutorials
  "Curated catalog of guided tutorials. Each tutorial is
  `{:id :title :description :category :difficulty :steps [...]}` where
  each step is `{:title :body :hint}`. Categories: Getting started,
  Layout, Components, Prototyping, AI, Export. Difficulty: beginner /
  intermediate / advanced."
  [{:id          :welcome-to-ovion
    :title       "Welcome to Ovion"
    :description "Tour the workspace, the canvas, panels, and the AI bar."
    :category    "Getting started"
    :difficulty  "beginner"
    :steps
    [{:title "Meet the canvas"
      :body  "The center area is your infinite canvas. Pan by holding the space bar and dragging; zoom with the trackpad or Ctrl/⌘ + scroll."
      :hint  "Try panning now — press space, drag, release space."}
     {:title "The left panel"
      :body  "The left sidebar holds the Layers list and the Assets library (components, graphics, design tokens). Switch between them with the tabs at the top."
      :hint  "Click the Layers tab to see the page's shape tree."}
     {:title "The right panel"
      :body  "The right sidebar is the Inspector — it shows design properties (fill, stroke, typography, layout) for whatever you have selected on the canvas."
      :hint  "Select an empty area of the canvas and notice the Inspector goes quiet."}
     {:title "The AI bar"
      :body  "The floating bar above the canvas is the Ovion AI bar. Type a prompt to generate screens, edit a selection, or ask a question. The mode icon on the right toggles prompt vs. edit mode."
      :hint  "You'll use the AI bar in a later tutorial — for now, just locate it."}]}

   {:id          :first-frame
    :title       "Create your first frame"
    :description "Frames are the artboards that hold your design. Learn to create, size, and arrange them."
    :category    "Getting started"
    :difficulty  "beginner"
    :steps
    [{:title "Pick the frame tool"
      :body  "Press F on the keyboard, or click the frame icon in the toolbar. The cursor becomes a crosshair."
      :hint  "The frame tool is the fastest way to start a layout."}
     {:title "Draw a frame"
      :body  "Click and drag on the canvas to draw a rectangle. Release to create the frame. Frames are containers — they clip their children."
      :hint  "Start near the top-left and drag a phone-sized rectangle."}
     {:title "Set a precise size"
      :body  "With the frame selected, look at the Inspector on the right. Enter 375 for width and 812 for height to make an iPhone-sized frame."
      :hint  "Press Tab to move between the W and H fields."}
     {:title "Add a shape inside"
      :body  "Press R for the rectangle tool and draw a shape INSIDE the frame. Because it's a child of the frame, it stays clipped within the frame's bounds."
      :hint  "If you draw outside the frame it becomes a sibling, not a child."}
     {:title "Rename the frame"
      :body  "Double-click the frame's name in the Layers panel (left) and type 'Login screen'. Naming frames keeps large files navigable."
      :hint  "Good names make the Layers panel a table of contents."}]}

   {:id          :auto-layout-basics
    :title       "Auto-layout basics"
    :description "Auto-layout (flex) makes a container arrange its children automatically. Build a row of buttons that never drift."
    :category    "Layout"
    :difficulty  "beginner"
    :steps
    [{:title "Create three shapes"
      :body  "Draw three small rectangles on the canvas, scattered. They don't need to be aligned yet."
      :hint  "Press R, draw, press R again, draw — three loose rectangles."}
     {:title "Select all three"
      :body  "Drag a marquee around the three shapes, or shift-click each one. The Inspector now shows multi-select options."
      :hint  "The selection order matters — the first shape you pick becomes the layout's first child."}
     {:title "Apply flex layout"
      :body  "In the Inspector, under Layout, click the 'Flex' button. The three shapes snap into a neat row inside a new flex container."
      :hint  "The container appears as a parent in the Layers panel."}
     {:title "Switch direction and gap"
      :body  "Toggle the direction from horizontal to vertical, then set the gap to 12. The children reflow instantly with even spacing."
      :hint  "Gap is the space BETWEEN children, not around them."}
     {:title "Add padding"
      :body  "Set 16px padding on all sides. The container grows to wrap its children with breathing room — exactly like CSS padding."
      :hint  "Padding is inside the container; the border draws on its outer edge."}]}

   {:id          :flex-responsive
    :title       "Flex layout for responsive design"
    :description "Use flex-wrap, alignment, and fill to make layouts that adapt like real UI."
    :category    "Layout"
    :difficulty  "intermediate"
    :steps
    [{:title "Start with a flex row"
      :body  "Create a flex row with four small rectangles as children, gap 8, horizontal direction."
      :hint  "Re-use what you learned in 'Auto-layout basics'."}
     {:title "Enable wrap"
      :body  "In the Inspector, turn on 'Wrap'. Now resize the container narrower — the children flow onto a second line instead of shrinking."
      :hint  "Wrap mirrors CSS `flex-wrap: wrap`."}
     {:title "Align items"
      :body  "Set 'Align items' to center and 'Justify content' to space-between. The children center vertically and push to the edges horizontally."
      :hint  "Justify controls the main axis; align controls the cross axis."}
     {:title "Make a child fill"
      :body  "Select one child and set its width to 'Fill' (the fill control in the Inspector). It now grows to take whatever horizontal space the other children leave."
      :hint  "Fill is the visual equivalent of `flex: 1`."}
     {:title "Nest a column inside"
      :body  "Add a flex COLUMN inside one of the children, with two text layers. You now have a card: a row of cells, one of which is a stacked text block."
      :hint  "Nesting flex in flex is how real UI is built."}
     {:title "Test with resize"
      :body  "Drag the container's edge wider and narrower. The layout reflows at every size with no manual nudging — that's responsive design."
      :hint  "If anything drifts, check that wrap and fill are both set."}]}

   {:id          :build-button-component
    :title       "Build a button component"
    :description "Components are reusable design elements. Build a button once and reuse it everywhere."
    :category    "Components"
    :difficulty  "beginner"
    :steps
    [{:title "Draw a button shape"
      :body  "Press R and draw a 160 x 48 rectangle. Give it a coral fill (#f28b82) and a 12px corner radius via the Inspector."
      :hint  "The radius control is under the Corner section in the Inspector."}
     {:title "Add a text label"
      :body  "Press T and click inside the rectangle. Type 'Get started'. Center the text and set it to white, 15px, semibold."
      :hint  "The text should sit on top of the rectangle, not inside it as a child yet."}
     {:title "Group into a flex row"
      :body  "Select both the rectangle and the text, then apply a flex row layout. Set gap 0, padding 0, align center, justify center. The text centers in the button."
      :hint  "Justify center + align center centers the label in both axes."}
     {:title "Create the component"
      :body  "With the group selected, press Ctrl/⌘+Alt+K (or use the Inspector's 'Create component' button). The group becomes a component — the master you'll reuse."
      :hint  "Components have a pink dashed outline in the Layers panel."}
     {:title "Drag instances"
      :body  "From the Assets panel (left sidebar), drag the new component onto the canvas twice. Each is an INSTANCE — edit the master and every instance updates."
      :hint  "Instances show a cyan indicator; the master shows pink."}]}

   {:id          :component-variants
    :title       "Component variants and properties"
    :description "One component, many states. Build a button with variants and a text property."
    :category    "Components"
    :difficulty  "intermediate"
    :steps
    [{:title "Open the component set"
      :body  "Select your button component from the previous tutorial. In the Inspector, click 'Add variant'. A second copy appears beside it inside a component set."
      :hint  "A component set is the parent that groups variants."}
     {:title "Style the second variant"
      :body  "Select the new variant and change its fill to a neutral grey. This is your 'secondary' state. The master set now holds two looks."
      :hint  "Variants share structure; only the styled props differ."}
     {:title "Add a text property"
      :body  "In the Inspector, under component properties, click '+' and add a 'Text' property named 'Label' with default 'Get started'. Wire the text layer's content to it."
      :hint  "Text properties let each instance show different copy."}
     {:title "Add a boolean property"
      :body  "Add a 'Boolean' property named 'Disabled'. Bind it to the fill opacity so true = 50% opacity. Now each instance can be enabled or disabled."
      :hint  "Boolean properties toggle a style on/off per instance."}
     {:title "Swap variants on an instance"
      :body  "Drag the component set onto the canvas to make an instance. In the Inspector, use the variant dropdown to switch between primary and secondary."
      :hint  "Variant swap is instant — the instance keeps its own property values."}
     {:title "Override the label"
      :body  "On one instance, change the 'Label' property to 'Sign up'. The instance shows its own text without touching the master."
      :hint  "Overrides are per-instance; the master stays clean."}]}

   {:id          :prototype-interaction
    :title       "Add a prototype interaction"
    :description "Wire two frames together with a click-to-navigate interaction and preview the flow."
    :category    "Prototyping"
    :difficulty  "intermediate"
    :steps
    [{:title "Create two frames"
      :body  "Build two frames side by side: 'Login' and 'Home'. Put a button shape on the Login frame."
      :hint  "Name frames in the Layers panel so the flow is readable."}
     {:title "Switch to Prototype mode"
      :body  "Click the mode toggle in the top bar (or press Shift+C) to enter Prototype mode. Frames now show connection handles on their edges."
      :hint  "The toggle is next to the Design/Code mode switch."}
     {:title "Drag a connection"
      :body  "Hover the button on Login, then drag from the circle handle that appears onto the Home frame. Release to create a connection."
      :hint  "The handle glows when you can drop on a target frame."}
     {:title "Set the interaction"
      :body  "With the connection selected, the Inspector shows the interaction: trigger 'On click', action 'Navigate to', destination 'Home'. Leave the transition as 'Dissolve'."
      :hint  "Triggers also include On hover, On press, and After delay."}
     {:title "Preview the flow"
      :body  "Click the Play icon in the top bar to open the preview window. Click your button — the preview navigates from Login to Home with the dissolve transition."
      :hint  "Preview renders the real interactions, not a static frame."}]}

   {:id          :generate-screen-with-ai
    :title       "Generate a screen with AI"
    :description "Use the Ovion AI bar to generate a complete screen from a text prompt."
    :category    "AI"
    :difficulty  "beginner"
    :steps
    [{:title "Open the AI bar"
      :body  "The floating AI bar sits above the canvas. Click into its input field to focus it. The mode icon on the right shows whether you're in prompt (generate) or edit mode."
      :hint  "If the bar is hidden, it appears when you press the AI shortcut."}
     {:title "Write a clear prompt"
      :body  "Type: 'A mobile login screen with a logo at the top, an email field, a password field, and a coral sign-in button.' Specific prompts give specific results."
      :hint  "Mention layout, the fields, and the accent color."}
     {:title "Generate"
      :body  "Press Enter or click the coral send arrow. The AI bar shows a busy state while the model plans the screen, then frames and shapes appear on the canvas."
      :hint  "Generation is non-destructive — it adds new shapes, never edits your selection."}
     {:title "Refine with edit mode"
      :body  "Select one of the generated fields, switch the AI bar to edit mode (the mode icon), and type 'make the corners rounder'. The AI edits only the selection."
      :hint  "Edit mode = surgical changes to what's selected; prompt mode = new content."}]}

   {:id          :export-to-code
    :title       "Export to code"
    :description "Turn a frame into ready-to-paste frontend code for the framework of your choice."
    :category    "Export"
    :difficulty  "intermediate"
    :steps
    [{:title "Select a frame"
      :body  "Click the frame you want to export so it's the only thing selected. Export works on the selection, not the whole page."
      :hint  "A single frame keeps the generated code focused."}
     {:title "Open the code export"
      :body  "In the Inspector, find the 'Export code' action (or use the right-click menu's Code export entry). The code export panel opens."
      :hint  "Code export is separate from image/PDF export."}
     {:title "Pick a framework"
      :body  "Choose a target from the framework list (HTML/CSS, React, Vue, Svelte, Tailwind, Angular, or Bootstrap). The preview updates for each."
      :hint  "Each target produces idiomatic, copy-pasteable code."}
     {:title "Copy or download"
      :body  "Click 'Copy' to put the code on the clipboard, or 'Download' to save it as a file. Paste it into your project and it renders matching the design's tokens."
      :hint  "The export preserves auto-layout as real flex CSS."}
     {:title "Verify the result"
      :body  "Paste the copied code into a blank HTML/JSX file and open it. Compare it side by side with your Ovion frame — the layout, colors, and text should match."
      :hint  "Mismatches usually come from unsupported effects — report them."}]}])

(defn tutorial-by-id
  "Look up a tutorial map by id keyword. Returns nil if not found."
  [id]
  (some #(when (= id (:id %)) %) tutorials))

;; --- Events ------------------------------------------------------------------

(defn toggle-workshop
  "Flip the workshop overlay's visibility. When opening, the library view
  is shown (no active tutorial). Byte-identical to the prior state when
  the workshop was already closed and this toggles it closed again — the
  overlay renders nothing while `:workshop-open?` is false."
  []
  (ptk/reify ::toggle-workshop
    ptk/UpdateEvent
    (update [_ state]
      (let [next-open (not (get state :workshop-open?))]
        (if next-open
          (-> state
              (assoc :workshop-open? true)
              (assoc :workshop-active-tutorial nil)
              (assoc :workshop-step 0))
          (-> state
              (assoc :workshop-open? false)
              (assoc :workshop-active-tutorial nil)
              (assoc :workshop-step 0)))))))

(defn close-workshop
  "Close the workshop overlay and reset to the library view."
  []
  (ptk/reify ::close-workshop
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc :workshop-open? false)
          (assoc :workshop-active-tutorial nil)
          (assoc :workshop-step 0)))))

(defn back-to-library
  "Leave the tutorial runner and return to the library view WITHOUT closing
  the workshop overlay and WITHOUT marking the tutorial completed. The
  in-progress step is already persisted by `set-step`/`advance-step`."
  []
  (ptk/reify ::back-to-library
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc :workshop-active-tutorial nil)
          (assoc :workshop-step 0)))))

(defn start-tutorial
  "Begin running tutorial `id`. Reads the persisted step for this tutorial
  (so a resumed tutorial jumps to where the user left off) and clears any
  completed flag for a fresh run."
  [id]
  (ptk/reify ::start-tutorial
    ptk/UpdateEvent
    (update [_ state]
      (let [progress (read-progress)
            entry    (get progress id)
            step     (if entry (:step entry) 0)]
        (assoc state
               :workshop-active-tutorial id
               :workshop-step step)))))

(defn set-step
  "Jump to an absolute step index (clamped to the tutorial's range). Used
  by Back/Next. Persists the new step."
  [step-index]
  (ptk/reify ::set-step
    ptk/UpdateEvent
    (update [_ state]
      (let [id        (get state :workshop-active-tutorial)
            tutorial  (tutorial-by-id id)
            max-step  (when tutorial (dec (count (:steps tutorial))))
            step      (if max-step
                        (max 0 (min step-index max-step))
                        0)]
        (when (and id tutorial)
          (persist-tutorial! id step false))
        (assoc state :workshop-step step)))))

(defn advance-step
  "Move to the next step. If this was the last step, mark the tutorial
  completed and return to the library view."
  []
  (ptk/reify ::advance-step
    ptk/UpdateEvent
    (update [_ state]
      (let [id        (get state :workshop-active-tutorial)
            tutorial  (tutorial-by-id id)
            cur       (get state :workshop-step 0)
            max-step  (when tutorial (dec (count (:steps tutorial))))]
        (if (nil? id)
          state
          (if (and max-step (>= cur max-step))
            ;; Last step -> complete.
            (do
              (persist-tutorial! id max-step true)
              (-> state
                  (assoc :workshop-active-tutorial nil)
                  (assoc :workshop-step 0)))
            (let [next-step (inc cur)]
              (persist-tutorial! id next-step false)
              (assoc state :workshop-step next-step))))))))

(defn complete-tutorial
  "Mark the active tutorial completed and return to the library view."
  []
  (ptk/reify ::complete-tutorial
    ptk/UpdateEvent
    (update [_ state]
      (let [id       (get state :workshop-active-tutorial)
            tutorial (tutorial-by-id id)
            cur      (get state :workshop-step 0)]
        (when (and id tutorial)
          (persist-tutorial! id cur true))
        (-> state
            (assoc :workshop-active-tutorial nil)
            (assoc :workshop-step 0))))))

(defn reset-progress
  "Clear persisted progress for tutorial `id` (or all tutorials when `id`
  is nil or `::all`). Does not change the running tutorial."
  ([]
   (reset-progress ::all))
  ([id]
   (ptk/reify ::reset-progress
     ptk/UpdateEvent
     (update [_ state]
       (clear-tutorial-progress! id)
       state))))