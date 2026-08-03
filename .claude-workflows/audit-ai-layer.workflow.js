export const meta = {
  name: 'audit-ai-layer',
  description: 'Audit Ovion AI tool-calling gap; produce authoritative fix spec (tool registry, Rust protocol, CLJS agent loop, scene/capture, i18n, verify checklist)',
  phases: [
    { title: 'Audit', detail: '9 parallel read-only agents map the gap + the exact dw/* signatures the fix must target' },
  ],
}

// Structured finding schema for every agent.
const FINDING_SCHEMA = {
  type: 'object',
  properties: {
    section: { type: 'string', description: 'Name of the audit area' },
    summary: { type: 'string', description: '2-4 sentence gap summary + the fix direction' },
    items: {
      type: 'array',
      description: 'Tools/protocol-points/verify-items this area contributes',
      items: {
        type: 'object',
        properties: {
          name: { type: 'string', description: 'tool name (snake_case) or protocol/verify point' },
          purpose: { type: 'string', description: 'what user capability this exposes to the AI' },
          jsonSchema: { type: 'string', description: 'sketch of the JSON-schema parameters the model passes (properties + types), as a compact string' },
          frontendEvent: { type: 'string', description: 'EXACT Clojure symbol the tool emits, e.g. dw/update-shape-geometry — verified from source' },
          eventArgs: { type: 'string', description: 'EXACT arg shape that event takes, as read from its defmethod/reify — verbatim where possible' },
          reads: { type: 'string', description: 'refs/state the tool reads (e.g. refs/workspace-page-objects), if any' },
          returns: { type: 'string', description: 'what the tool returns to the model as a result message' },
          files: { type: 'array', items: { type: 'string' }, description: 'source files this finding is grounded in' },
          risk: { type: 'string', description: 'no-build static-verification risk for this item + how to mitigate' },
        },
        required: ['name', 'purpose', 'frontendEvent', 'eventArgs', 'files'],
      },
    },
    protocolNotes: { type: 'string', description: 'cross-cutting protocol/structure notes for the Rust/CLJS agent loop (only for protocol agents)' },
    verifyNotes: { type: 'string', description: 'static-verify checks specific to this area (bracket/symbol/contract/reduced-motion/no-build)' },
  },
  required: ['section', 'summary', 'items', 'verifyNotes'],
}

const ROOT = 'D:/TestProjects/Penpot-desktop/penpot-source/frontend/src/app/main'

phase('Audit')

const PROMPTS = [
  {
    label: 'rust-protocol',
    prompt: `Read D:/TestProjects/Penpot-desktop/src-tauri/src/llm.rs in FULL (1704 lines). It is the Ovion AI backend. Today it is pure prompt->text->first-balanced-JSON with NO tool calling. The fix adds a real tool-calling agent loop DRIVEN FROM CLJS, with Rust as a stateless model-call service.

Specify the EXACT Rust additions needed (as protocol notes + items, not full code):
1. A new #[tauri::command] async fn llm_agent_step(app, request: AgentStepRequest) -> Result<AgentStepResponse, String> that does ONE model call and returns either a DesignSpec, a tool_calls list, or text. Reuse the existing provider dispatch (call_deepinfra/call_ovion_cloud/call_ollama), extract_json, ABORT/cancellation, and memory plumbing.
2. The new request/response serde structs: AgentStepRequest {messages: Vec<ChatMessage>, tools: Option<serde_json::Value>, options: GenerateOptions, files: Vec<ImageInput>}; ChatMessage supporting role system|user|assistant|tool + content (text or multimodal array) + optional tool_calls + optional tool_call_id; AgentStepResponse with kind "spec"|"tool_calls"|"text"|"error" and the right Option fields. Give exact field names + serde rename attributes.
3. How to add the "tools" + "tool_choice":"auto" field to EACH provider body (DeepInfra/Ovion OpenAI shape, and Ollama /api/chat which supports tools), and how to parse tool_calls from each provider response (the OpenAiMessage struct currently has only content:String — add Option<tool_calls> with the right serde shape; Ollama message.tool_calls).
4. How to keep llm_generate byte-identical-when-inactive (the legacy spec-only path stays; agent loop is additive). How cancellation (ABORT) + ai-progress events are reused. Whether response_format:json_object must be omitted when tools are present (OpenAI: can't force json_object AND tools simultaneously — decide the rule).

Read lib.rs:752-761 for the invoke_handler registration so you specify where to add llm_agent_step. Output the Rust diff spec as items + protocolNotes. verifyNotes = the Rust static checks (serde shape, Option correctness, no unused imports, no-build).`,
  },
  {
    label: 'cljs-agent-loop',
    prompt: `Read D:/TestProjects/Penpot-desktop/penpot-source/frontend/src/app/main/data/workspace/ai_gen.cljs (365, full) and D:/TestProjects/Penpot-desktop/penpot-source/frontend/src/app/main/data/workspace/design_gen.cljs (299, full) and D:/TestProjects/Penpot-desktop/penpot-source/frontend/src/app/main/ui/workspace/ai_bar.cljs.

Today generate-design is a single invoke->preview->apply. The fix adds a CLJS-driven agent loop (new file ai_agent.cljs OR additive in ai_gen.cljs) that: builds messages (system + user{prompt + scene-context + optional viewport image}), calls llm_agent_step, executes returned tool_calls against the live scene graph via st/emit! dw/*, appends tool results, loops (max N), ends on spec/text/error/cancel.

Specify (items + protocolNotes):
1. The exact potok events needed: an agent-step WatchEvent, tool-call progress UpdateEvents (set-ai-stage with tool name), reuse of gen-id stale-guard, set-ai-preview/set-ai-error. Exact symbols + state keys under :workspace-local.
2. The loop structure: where iteration cap lives, how cancel propagates (gen-id + ABORT), how each tool result message is shaped (role:tool, tool_call_id, content: JSON result string).
3. How the bar's on-generate branches into the agent loop WITHOUT changing the bar's visuals (the UI must stay reference-pinned). A new option :agent? or a mode; the existing spec-only path preserved.
4. The tool registry contract: a map name->{:schema :execute} where execute is (fn [args] <result-object>) that emits dw/* and returns a plain result. Where it lives (new ai_tools.cljs).
5. Prompts: a DESIGN_AGENT_SYSTEM prompt that tells the model it can call tools OR emit a DesignSpec; how scene-context is embedded; how viewport image attaches.

Ground every symbol in the files you read. verifyNotes = bracket-balance plan for the new CLJS, gen-id/cancel safety, no state keys collide.`,
  },
  {
    label: 'tools-drawing-styling',
    prompt: `Audit the AI's gap on drawing/styling/transform capabilities. Read these in ${ROOT}/data/workspace/: shapes.cljs (or the main data/workspace namespace — find it), interpolation.cljs if present, and grep for the defmethods/reify of these dw/* events with their EXACT arg shapes: dw/create-shape, dw/update-shape, dw/update-shape-geometry, dw/move-shapes, dw/resize-shapes, dw/rotate-shapes, dw/flip-horizontal-selected, dw/flip-vertical-selected, dw/delete-selected, dw/delete-shapes, dw/duplicate-selected, dw/align-objects, dw/distribute-objects, dw/vertical-order-selected, dw/update-shape (for fills/stroke/shadow/blur/radius/opacity), dw/update-fill, dw/update-stroke, dw/update-shadow, dw/nudge-selected, dw/convert-selected-to-path.

Also read ${ROOT}/ui/workspace/sidebar/options/menus/{fill,stroke,shadow,blur,border_radius,measures}.cljs to see what props each styling control sets (the exact map keys: :fills/:stroke/:shadow/:blur/:rx/:ry/:opacity/:x/:y/:width/:height/:rotation).

For EACH tool the AI needs (create_shape, update_shape, delete_shape, duplicate_shape, move_shape, resize_shape, rotate_shape, flip_shape, set_fill, set_stroke, set_shadow, set_blur, set_radius, set_opacity, align_objects, distribute_objects, order_shape, nudge_shape, set_geometry), give: the EXACT dw/* symbol, the EXACT args map shape (verbatim from the reify/defmethod), what it reads, what result string it returns, and the no-build risk. This is the highest-volume tool cluster — be exhaustive and ground every signature in source.`,
  },
  {
    label: 'tools-text-typography',
    prompt: `Audit the AI's gap on text/typography. Read ${ROOT}/data/workspace/text.cljs (or wherever dw/update-text / dw/update-typography live — grep), ${ROOT}/ui/workspace/sidebar/options/menus/typography.cljs, ${ROOT}/ui/workspace/sidebar/options/shapes/text.cljs, and common types: D:/TestProjects/Penpot-desktop/penpot-source/common/src/app/common/types/text.cljc and typography.cljc.

Find the EXACT events + arg shapes for: setting text content (dw/update-text or the text editor commit path), font family/size/weight, line-height, letter-spacing, text-align, text-decoration, text-transform, direction, text-overflow (truncate/ellipsis/max-lines), hyperlink, subscript/superscript, vertical align, grow mode, rich-text runs (per-range styling), and the rename path (dw/rename-shape-or-variant — verify its exact signature in data/workspace/interactions or wherever; the ai_settings rename uses it).

For tools: set_text, set_typography, set_text_align, set_text_decoration, set_text_overflow, set_hyperlink, set_rich_text, rename_shape — give the EXACT dw/* symbol + verbatim arg shape + reads + returns + risk. Also specify how the AI reads a text shape's current content (the shape :content / txt/content->text path) so it can preserve/edit existing copy.`,
  },
  {
    label: 'tools-layout-constraints',
    prompt: `Audit the AI's gap on layout/constraints/grid. Read ${ROOT}/data/workspace/layout.cljs or wherever dwsl/* lives (grep for dwsl/create-layout, dwsl/update-layout, dwsl/remove-layout, dwsl/merge-cells, dwsl/create-cell-board, dwsl/add-layout-track, dwsl/remove-layout-track, dwsl/duplicate-layout-track, dwsl/copy-grid-tracks, dwsl/paste-grid-tracks). Read ${ROOT}/ui/workspace/sidebar/options/menus/{layout_container,layout_item,constraints,frame_grid,grid_cell}.cljs and common types: ctl (app.common.types.layout / flex / grid) — find the exact map shapes for :layout (flex) and :layout-grid (grid) and :layout-item-props.

For tools: add_flex_layout, add_grid_layout, update_layout, remove_layout, set_child_layout_props (min/max W/H, margin, align-self, position, absolute pin), set_constraints (H/V axis, fix-when-scrolling), grid_merge_cells, grid_create_cell_board, grid_add_track, grid_delete_track, grid_duplicate_track — give the EXACT dwsl/* symbol + verbatim arg shape + the :layout/:layout-grid map shape + reads + returns + risk. Note which tools are disabled when parent has flex vs grid.`,
  },
  {
    label: 'tools-component-variant-interaction',
    prompt: `Audit the AI's gap on components/variants/interactions/prototype. Read the EXACT events: dwl/add-component, dwl/add-multiple-components, dwv/combine-selected-as-variants, dwv/add-new-variant, dwv/add-variant-property (grep data/workspace/), dwl/swap-component (or the swap path in component.cljs), dw/update-component-annotation, dw/copy-selected-css (code-connect), and the interaction events dwi/add-interaction, dwi/update-interaction, dwi/remove-interaction, dwi/add-flow-selected-frame, dwi/remove-flow.

Read ${ROOT}/ui/workspace/sidebar/options/menus/{component,interactions}.cljs and ${ROOT}/ui/workspace/context_menu.cljs for the interaction map shape ({:event-type :action-type :destination :animation :easing :duration :delay :overlay-position ...}) and the typed-component-property schema (app.common.types.component_property — :boolean/:text/:instance-swap/:variant/:slot).

For tools: create_component, create_multiple_components, combine_as_variants, add_variant, add_variant_property, swap_component, set_component_annotation, code_connect, add_interaction, update_interaction, remove_interaction, set_flow_start, remove_flow — give the EXACT symbol + verbatim arg shape + the interaction map keys + reads + returns + risk.`,
  },
  {
    label: 'tools-layers-pages-export-clipboard',
    prompt: `Audit the AI's gap on layers/pages/export/clipboard/grouping/masking/z-order. Read the EXACT events: dw/select-shape, dw/select-shapes, dw/select-all, dw/start-rename-selected, dw/update-shape-flags (hidden/blocked), dw/group-selected, dw/ungroup-selected, dw/mask-group, dw/unmask-group, dwsh/update-shapes (mask-mode alpha/vector/luminance), dwsh/create-artboard-from-selection, dw/tidy-up, dw/toggle-focus-mode, dw/copy-selected, dw/paste-from-clipboard, dw/copy-selected-css, dw/copy-selected-svg, dw/copy-as-image, dw/copy-selected-props, dw/paste-selected-props, dw/copy-link-to-clipboard, dw/copy-id-to-clipboard, dw/duplicate-page, dw/delete-page, the page navigation (dcm/go-to-workspace :page-id), dw/rename-file.

Read ${ROOT}/ui/workspace/sidebar/{layers,sitemap}.cljs and ${ROOT}/ui/workspace/context_menu.cljs. Also the export path: de/show-workspace-export-dialog, fexp/open-export-dialog (from main_menu.cljs) — what args.

For tools: select_shape, select_all, toggle_visibility, toggle_lock, group, ungroup, mask, unmask, set_mask_mode, create_artboard_from_selection, tidy_up, toggle_focus, copy, paste, copy_as (css/svg/image/props), duplicate_page, delete_page, navigate_page, rename_file, export_selection — give the EXACT symbol + verbatim arg shape + reads + returns + risk. For clipboard ops note which are fire-and-forget (no result) vs return data.`,
  },
  {
    label: 'scene-serializer',
    prompt: `Audit the scene-graph serialization the AI needs to "understand what's visible via code". Read D:/TestProjects/Penpot-desktop/penpot-source/frontend/src/app/main/refs.cljs (find workspace-page-objects, selected-shapes, current-file-id, workspace-page-objects — the exact ref names), and the common shape type: D:/TestProjects/Penpot-desktop/penpot-source/common/src/app/common/types/shape.cljc (the :shape schema — what keys every shape has: :id :type :name :x :y :width :height :x1/:y1... :rotation :fills :stroke :shadow :blur :rx :ry :opacity :typography :content :layout :layout-item-props :shapes (children) :component-id :component-file :shape-id etc). Also read design_gen.cljs selection->snippet (lines ~71) — the existing per-shape snippet serializer — to reuse its field set.

Specify the serializer (items): a serialize-scene fn that walks refs/workspace-page-objects (a map id->shape) producing a TOKEN-BOUNDED structured text (or JSON) of the visible/relevant shapes: id, type, name, x/y/w/h, rotation, fills (summary), stroke (summary), typography (family/size/weight/align), content (text), layout (flex/grid summary), children ids, component-id. Cap: max shapes, max depth, max content length, skip hidden. Specify the exact ref symbols, the shape keys read, the text format the model gets, and the bounding strategy. Ground every key in shape.cljc. verifyNotes = token-budget guard + no infinite recursion on cycles.`,
  },
  {
    label: 'viewport-capture-visual',
    prompt: `Audit the "understand via visuals" channel: capture the workspace viewport as a PNG and send it to the vision model (Kimi) as an image_url. Read ${ROOT}/ui/workspace/viewport.cljs and viewport_wasm.cljs to find the root SVG element the workspace renders into (its id/selector, the <svg> root, viewBox, defs). Also check ${ROOT}/ui/inspect/code.cljs and ${ROOT}/data/exports/code.cljs for any existing rasterize/screenshot path (resolve-rasters! uses a backend export RPC — can it screenshot the viewport?).

Specify the capture pipeline (items): a capture-viewport-png CLJS fn that (a) locates the viewport root SVG, (b) XMLSerializer.serializeToString, (c) loads it into an off-DOM Image via a Blob URL (with proper xmlns + width/height + viewBox), (d) draws to an off-DOM canvas at device pixel ratio, (e) canvas.toDataURL("image/png") -> base64. Cover edge cases: fonts (the proxy-served gfonts must be inlined or the rasterization drops them — decide: inline <style> with the cached @font-face, or accept fallback), foreignObject/images (tainted canvas — skip cross-origin images), viewBox scaling, max dimension cap (e.g. 1600px) + quality.

Also: decide whether a Tauri command (webview screenshot) is better/safer than the SVG->canvas path under no-build. Recommend the path with lower static-verify risk. Give the exact DOM selectors/refs + the fn contract + the image attachment shape sent to llm_agent_step (file {name mime base64}). Ground the SVG root in viewport.cljs. verifyNotes = tainted-canvas, font rendering, no-build risk.`,
  },
  {
    label: 'i18n-ui-verify',
    prompt: `Audit the i18n + UI integration + the full static-verify checklist for the fix. Read D:/TestProjects/Penpot-desktop/penpot-source/frontend/translations/en.po (the central i18n; find the workspace.ai.* block) and ${ROOT}/ui/workspace/ai_bar.cljs (the stage-text rendering around subscribe-progress, lines ~513-527) + ai_settings.cljs.

Specify (items):
1. The new en.po keys needed: agent-loop stages (tool-thinking, executing-tool with %s tool name, agent-done, agent-max-iterations, agent-cancelled), errors (tool-not-found, tool-failed), and any UI text. Exact msgid strings + the %s rules (tr supports %s; dm/fmt=cuerdas does NOT).
2. How the bar shows agent progress WITHOUT changing visuals (reuse the existing stage line; the stage string just carries the tool name). Confirm no new DOM, no visual change.
3. The complete static-verify checklist (verifyNotes) the final pass must run: (a) bracket/paren balance on every touched CLJS file (char-level string/comment-aware scanner), (b) Rust serde/symbol/Option check, (c) reduced-motion guard untouched (ai_motion.cljs unchanged), (d) no-build invariant (no resources/public/ edits, source only), (e) byte-identical-when-inactive (AI inactive = base Penpot; legacy llm_generate path preserved), (f) tool-registry coverage vs the ~150-capability baseline (count covered/total), (g) gen-id/cancel/termination safety, (h) no new requires that break the ns, (i) en.po CRLF + no dangling keys.
4. List the files the fix will touch (expected file list) so the verify pass knows its scope.`,
  },
]

const results = await parallel(
  PROMPTS.map((p) => () =>
    agent(p.prompt, { label: p.label, phase: 'Audit', schema: FINDING_SCHEMA, effort: 'high' })
  )
)

return results.filter(Boolean)