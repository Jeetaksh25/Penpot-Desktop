# Figma Parity — Gap Analysis & Implementation Plan

> **Purpose.** Complete context for every feature the latest Figma (2025–2026) has that this product (a Penpot v2 desktop fork, wrapped in Tauri, with added Feature 1 fonts / Feature 2 code export / Feature 3+4 AI) does **not** have, or has only partially / weaker. This doc drives a full end-to-end implementation pass, then a 2-round checkup (bugs/diffs → fix → verify, then polish).
>
> **Derived from.** A 13-agent research workflow: 6 agents researched Figma's full feature surface (web, text-only — no images), 6 agents audited this codebase category-by-category (Glob/Grep/Read), then a synthesis agent cross-referenced the two into 78 ranked gaps. Each gap below carries the concrete files/symbols to touch.
>
> **Build constraints (still in effect).** *Commit & push allowed; do NOT run any build (`tauri build` / `pnpm build` / `cargo build` / `cargo check`) and do NOT view any images.* All implementation is static-authored + statically verified (adversarial review workflows), exactly as the AI-pipeline round was.
>
> **Status legend.** `absent` = no support; `partial` = some support but missing modes/controls; `present-but-weaker` = exists but materially behind Figma. **Priority:** P0 = core/Figma-defining, P1 = important, P2 = nice-to-have. **Complexity:** S (small) / M (medium) / L (large) / XL (architectural).

---

## Summary

| Priority | Count | Notable themes |
|---|---|---|
| **P0** | 8 | typed component properties, corner smoothing, stroke joins/miter, per-side strokes, conic gradient, image crop, slice tool, smart selection |
| **P1** | 34 | interactive components, smart animate, OpenType, variable-font axes, text lists/truncation/hyperlinks/text-on-path, gradient mesh, video fill, image adjustments, pattern fill, mask variants, outline stroke, shape builder, paint bucket, variables-in-prototyping, variable modes, reusable styles, more triggers/easing, device frames, in-canvas preview, scale tool, branching, sections, code connect, AI image editing |
| **P2** | 36 | sticky notes, outline/pixel-preview modes, command palette, spell check, multi-edit text, hanging punctuation, lasso, brush, variable-width/dynamic strokes, offset/simplify vector, polygon/star/arc tools, progressive blur, glass/noise/texture/shader effects, 3D, audio/cursor chat, copy-as-SVG, iOS+rem export, visual search, AI rename, prototype sections, export presets, dev-mode playground, super/subscript |

**Repo conventions used throughout the hints:**
- Common type schemas live in `penpot-source/common/src/app/common/types/` (extend existing schemas — never invent parallel ones).
- Workspace UI lives in `penpot-source/frontend/src/app/main/ui/workspace/` (toolbar `top_toolbar.cljs`, right sidebar `sidebar/options/menus/*.cljs`, viewport `viewport/`).
- Data events live in `penpot-source/frontend/src/app/main/data/workspace/`.
- Renderer is split: frontend SVG (`ui/shapes/`) + native `render-wasm` (rust) — both must be updated for any paint/effect change.
- Text editor is a separate package `frontend/text-editor/src/editor/` (`content/dom/TextSpan.js` maps attrs → CSS).
- This fork's additions: `src-tauri/src/llm.rs` (AI backend), Feature 2 `app.util.code-gen`, Feature 3 `…/workspace/ai_bar.cljs` + `data/workspace/ai_gen.cljs` + `design_gen.cljs`.

---

# P0 — Core / Figma-defining gaps

## 1. Typed component properties (boolean / text / instance-swap / variant / slot)  `[P0 / XL / absent]`
**Figma behavior.** Main components declare named, typed properties: Boolean (visibility toggle), Text (content), Instance-swap (with preferred instances), Variant (axis), Slot (content areas). Instances expose these as editable fields in the Properties panel; boolean/string variables can drive variant properties and auto-switch variants on mode change. Slots let you add/rearrange content within an instance without detaching.
**Implementation hint.** Penpot has variant-properties (free-form name/value STRINGS in `common/src/app/common/types/variant.cljc`) and UUID-based swap-slots (`component.cljc` `build-swap-slot-group`) but **no typed property system**. Create `common/src/app/common/types/component_property.cljc` with schemas for `:boolean/:text/:instance-swap/:variant/:slot`, add a `:component-properties` map to `schema:component` (`component.cljc`), and a `:component-property-values` map on instance shapes. Wire instance rendering in `frontend/src/app/main/ui/workspace/sidebar/options/menus/component.cljs` to expose/edit typed properties. Map boolean→layer visibility, text→`:touched` content-group, instance-swap→swap-slot, variant→variant-properties, slot→content-frame override. **This is the single biggest Figma-parity gap** and unblocks gaps #10, #42.

## 2. Corner smoothing (squircle / continuous-curve corners)  `[P0 / S / absent]`
**Figma behavior.** 0–100% smoothing applied to shape corners (0% = circular, 60% = iOS squircle, 100% = max); whole-shape only, requires radius > 0. API `cornerSmoothing` 0–1. iOS quick preset.
**Implementation hint.** Add `:corner-smoothing` (0..1 number, default 0) to the radius schema in `common/src/app/common/types/shape/radius.cljc` alongside `r1`–`r4`. Add a UI slider + iOS preset button in `frontend/.../sidebar/options/menus/border_radius.cljs`. Renderer must implement the superellipse/smoothing curve in `render-wasm` (rust) and in frontend SVG path generation. Export in `common/src/app/common/types/shape/attrs.cljc` `editable-attrs`.

## 3. Line joins (miter/round/bevel) + miter limit  `[P0 / S / absent]`
**Figma behavior.** Stroke joins: Miter (sharp), Bevel (flat), Round (soft), editable per-anchor in vector edit mode. Miter limit/angle threshold below which a miter bevels. First-class stroke properties in UI and API.
**Implementation hint.** `stroke-linejoin` and `stroke-miterlimit` currently exist only as SVG-passthrough attrs for `svg-raw` shapes (`common/src/app/common/types/shape/svg.cljc:367-368`, render-wasm serializers). Add `:stroke-linejoin [::sm/one-of #{:miter :round :bevel}]` and `:stroke-miterlimit` to `schema:stroke-attrs` in `common/src/app/common/types/shape.cljc` + `stroke.cljc`. Add a UI select + numeric input in `frontend/.../sidebar/options/menus/stroke.cljs`. Wire renderer passthrough for non-svg shapes.

## 4. Per-side stroke widths (independent top/right/bottom/left)  `[P0 / M / absent]`
**Figma behavior.** Custom stroke mode exposes four independent weight fields (Top/Bottom/Left/Right, 0 = no stroke on that side) on rectangles, frames, components, instances. API exposes `strokeTopWeight/strokeBottomWeight/strokeLeftWeight/strokeRightWeight`.
**Implementation hint.** `stroke-width` is a single scalar (`shape.cljc:141`). Add `:stroke-width-1..4` (or `:stroke-top/right/bottom/left`) to `schema:stroke-attrs` in `shape.cljc`, with a `:stroke-width-mode [:uniform :per-side]` toggle. Add per-side inputs in `stroke.cljs` / `stroke_row.cljs`. Renderer (render-wasm + SVG output) must draw per-edge strokes on rect/frame shapes. Reuse the `r1`–`r4` per-corner pattern from `radius.cljc` as a model.

## 5. Conic / angular gradient fill (and diamond gradient)  `[P0 / M / DONE-v1]`
**Figma behavior.** Angular/conic gradient sweeps clockwise from a start position through multi-stop colors around a center point. Diamond gradient is a 4-point gradient from center. Both are standard fill types alongside linear and radial.
**Implementation hint.** `gradient-types` in `common/src/app/common/types/color.cljc:92` is exactly `#{:linear :radial}`. Add `:angular` (conic) and `:diamond`. Extend `schema:gradient` with center point + start angle for conic, 4 corner points for diamond. Add gradient-type options in the frontend colorpicker `frontend/.../sidebar/options/menus/colorpicker/gradients.cljs`. Implement the conic sweep in render-wasm + SVG fallback (SVG `conicGradient` or path-based approximation). Stroke gradient (`shape.cljc:151` `stroke-color-gradient`) inherits `gradient-types` so it gains these too.

**DONE-v1 (commit pending).** `gradient-types` → `#{:linear :radial :angular :diamond}` (color.cljc). Renderer: new `angular-gradient` defc in `ui/shapes/gradients.cljs` — a 90-wedge SVG `<pattern>` (userSpaceOnUse, sized to shape bounds) swept around the center (:start-x/y) with the 0° ray toward :end-x/y, each wedge filled via `clr/interpolate-gradient`. Dispatch added in `gradients.cljs`, `fills.cljs`, `custom_stroke.cljs` (stroke-defs) so fills + strokes + per-side strokes all paint. `:diamond` approximated as radial in v1 (SVG has no native diamond gradient; wedge method does not apply). Colorpicker: `:angular-gradient`/`:diamond-gradient` options in `colorpicker/gradients.cljs`; tab mapping in `colorpicker.cljs` (`active-fill-tab` + `selected-mode`); reverse mapping `gradient :type → tab` + `tab → :type` in `colors.cljs` (`apply-color-from-colorpicker`, `get-color-from-colorpicker-state`). Display strings + CSS swatch preview (`conic-gradient(...)` / radial fallback) in `util/color.cljs`. i18n `workspace.gradients.angular/diamond` in en.po. **Known v1 limitations (→ polish #9):** (1) conic uses page-space coords so rotated shapes may not rotate the sweep with the shape (axis-aligned shapes — the common case — are correct); (2) `:diamond` is a radial approximation, not a true Manhattan-distance diamond; (3) on-canvas gradient width handle is hidden for angular/diamond (drag center + 0°-ray endpoint only).

## 6. Image crop (non-destructive per-image crop rect)  `[P0 / M / DONE-v1]`
**Figma behavior.** Double-click or sidebar Crop mode on an image fill reveals drag handles + aspect-ratio picker to non-destructively crop; cropped areas are preserved, not deleted. Alt adjusts opposite sides, Cmd/Ctrl+drag corners.
**Implementation hint.** `schema:image` in `common/src/app/common/types/color.cljc:75` has `width/height/mtype/id/keep-aspect-ratio` but **no crop rect**. Add `:crop-x/:crop-y/:crop-w/:crop-h` (normalized 0..1) or a `:crop-transform` matrix to `schema:image`. Add a Crop mode toggle + on-canvas crop handles in the frontend image fill UI (`fill.cljs` + a new crop overlay in the viewport). Renderer applies the crop rect as SVG `clipPath` / pattern `viewBox`. Reuse the existing fill transform/matrix infrastructure.

**DONE-v1 (commit pending).** Added optional `:crop-x/:crop-y/:crop-w/:crop-h` (normalized 0..1) to `schema:image` (color.cljc). Renderer (`ui/shapes/fills.cljs`): when an image has a valid crop rect, the `<image>` element is scaled/positioned so the crop region maps exactly onto `[0,0,width,height]` (the shape bounds / pattern tile); the uncropped borders land outside the tile and are clipped by the shape geometry — hidden, not deleted (reversible). Applied to both image sites (the `fills[]` image branch and the shape's own `:fill-image` padding branch). UI: a Crop section in the colorpicker image tab (colorpicker.cljs) with four percentage numeric inputs (X/Y/W/H) + a reset button, reusing the existing `update-colorpicker-color` event (the `:image` map flows straight to `:fill-image`, so crop fields persist on the fill and round-trip via `fill->color`/`get-color-from-colorpicker-state`). i18n `media.image-crop`/`media.image-crop-reset` (en.po) + scss layout (`colorpicker.scss`). **Known v1 limitations (→ polish #9):** crop is via numeric inputs only — on-canvas drag handles + aspect-ratio picker + Alt/Cmd-modifier corner drag are deferred.

## 7. Slice tool (export regions)  `[P0 / M / absent]`
**Figma behavior.** Region tool (shortcut S) creates custom export areas on the canvas; per-slice export settings (PNG/JPG/SVG/PDF); bulk export via File > Export.
**Implementation hint.** Add `:slice` to the toolbar tool set in `frontend/.../workspace/top_toolbar.cljs` (currently move/frame/rect/circle/line/arrow/text/image/path/curve/plugins/mcp/debug). Create a slice shape type in `common/src/app/common/types/shape.cljc` (a frame-like rect with `:type :slice` that renders only as an export region, not visible content). Add slice export handling in `frontend/.../ui/inspect/exports.cljs` and `data/exports/assets.cljs` (treat slice bbox as export bounds). Add shortcut `:slice` `S` in `data/workspace/shortcuts.cljs`.

## 8. Smart Selection (tidy up, pink handles, rearrange/resize reflow)  `[P0 / L / absent]`
**Figma behavior.** Auto-activates on 3+ uniformly-spaced items (1D row/column or 2D grid); pink handles adjust spacing, pink rings rearrange/resize items. Tidy Up (Opt/Ctrl+Alt+T) arranges into rows/columns/grids. Reorder/duplicate/resize/delete with reflow.
**Implementation hint.** Add detection of uniformly-spaced selections in `frontend/.../workspace/viewport/selection.cljs` (compute spacing uniformity across selected siblings). Render pink spacing handles + rearrange rings as viewport overlays. Add a Tidy Up action in `frontend/.../data/workspace.cljs` (`align-objects`/`distribute-objects` logic exists to build on — extend to grid arrangement). Add `:tidy-up` shortcut in `shortcuts.cljs`. Requires new viewport interaction handles, not just data-model changes.

---

# P1 — Important gaps

## 9. Per-item blend modes (per-fill, per-stroke, per-shadow, per-effect)  `[P1 / M / present-but-weaker]`
**Figma behavior.** 19 blend modes applicable per layer, per fill, per stroke, and per effect. Each fill/stroke/shadow carries its own blend mode (Normal default for fills/effects). Layer blur or unclipped texture isolates fill/stroke/shadow blend modes.
**Implementation hint.** `blend-modes` set (`shape.cljc:81`, 16 modes) is wired only to the shape-level `:blend-mode` attr (`frontend menus/layer.cljs`). Add `:blend-mode [::sm/one-of blend-modes]` to `schema:fill-attrs` (`fills.cljc`), `schema:stroke-attrs` (`shape.cljc`), `schema:shadow` (`shape/shadow.cljc`), `schema:blur`/`background-blur` (`shape/blur.cljc` + `background_blur.cljc`). Add blend-mode selects in `fill.cljs`, `stroke_row.cljs`, `shadow_row.cljs`, `blur.cljs`. Renderer must composite each paint/effect with its own blend mode (render-wasm + SVG `mix-blend-mode`).

## 10. Interactive components (change-to variant action + component state)  `[P1 / L / absent]`
**Figma behavior.** Interactive components swap variants in a component set on a trigger (e.g. on click change State=Hover). Components hold interactions that drive variant state changes, enabling button states, toggles, etc. without separate frames.
**Implementation hint.** `action-types` in `interactions.cljc:38-44` has no `:change-to`/`:set-variant`. Add `:change-to` to `action-types` with a schema referencing a target variant-component-id + variant-properties. Viewer dispatch in `frontend/.../ui/viewer/shapes.cljs` `activate-interaction` must swap the instance's variant on trigger. Component instances already carry `:interactions` (`component.cljc:148` `swap-keep-attrs`). Build on `variant.cljc` `variant-properties` + the existing instance swap mechanism. Requires variant resolution by matching property values. **Depends on #1.**

## 11. Smart animate (matched-property tweening)  `[P1 / L / absent]`
**Figma behavior.** Smart animate automatically tweens between matching layers across frames by layer name, interpolating position/size/rotation/opacity/fills — no manual keyframes needed.
**Implementation hint.** `animation-types` in `interactions.cljc:72-73` is `#{:dissolve :slide :push}`. Add `:smart-animate` to `animation-types` + schema. Implement in the viewer (`frontend/.../ui/viewer/interactions.cljs` `animate-go-to-frame`): match layers by name between source and destination frames, compute property diffs, use the Web Animations API (`dom/animate!`) to tween matching properties. Reuse the existing per-action animation infrastructure (duration/easing fields already present).

## 12. OpenType features + figure styles + small caps + super/subscript  `[P1 / L / absent]`
**Figma behavior.** Full OpenType panel: ligatures (LIGA/CLIG), discretionary ligatures (DLIG), contextual alternates (CALT), ordinals, stylistic sets ss01–ss20, character variants cv01–cv99, kerning (KERN). Figure styles: proportional/tabular figures, lining/old-style, fractions, slashed zero. Small caps. Superscript/subscript with faux fallback. Hover preview per feature; unsupported greyed out.
**Implementation hint.** No `font-feature-settings` anywhere (`text.cljc`, `TextSpan.js` STYLES, `typography.cljs`, render-wasm `text.rs`). Add `:font-feature-settings` map (feature-tag→boolean) to `text-span-attrs` and `text-node-attrs` in `common/src/app/common/types/text.cljc`. Map to CSS `font-feature-settings` in `frontend/text-editor/src/editor/content/dom/TextSpan.js` STYLES. Add an OpenType Details tab UI in `frontend/.../sidebar/options/menus/typography.cljs` (read available features from opentype.js font metadata in `fonts.cljs` — opentype.js is already loaded). Renderer (render-wasm `text.rs`) must pass features to font shaping. Group all OpenType/figure-style/small-caps/super-sub into one panel. (Super/subscript rendering also tracked at #78.)

## 13. Variable-font axes (continuous weight/width/optical-size/slant/italic/grade)  `[P1 / M / absent]`
**Figma behavior.** Single-file variable fonts with continuous axes adjustable via sliders in Type Settings Variable tab. Weight as continuous axis (e.g. 143 or 629), plus width, optical size, slant, italic, grade, custom axes. Dev Mode exports axis values as code.
**Implementation hint.** No `font-variation-settings` or axis model (`fonts.cljs` uses opentype.js only for metadata). Add `:font-variation-settings` map (axis-tag→number) to `text-span-attrs` in `text.cljc`. Read available axes from font metadata via opentype.js (extract `fvar` table axes). Add a Variable tab UI with per-axis sliders in `typography.cljs`. Map to CSS `font-variation-settings` in `TextSpan.js` STYLES. Renderer (render-wasm `text.rs`) must apply variation settings to font shaping.

## 14. Line-height modes (auto / percentage / px)  `[P1 / S / partial]`
**Figma behavior.** Line height with three modes: Auto (font default leading), Percentage of font size (%), Fixed pixels (px). Saved in text styles.
**Implementation hint.** `line-height` in `text.cljc:46` is a unitless multiplier only (rendered via `Style.js` dividing computed px by font-size). Add `:line-height-mode [:auto :percent :px]` alongside the existing `:line-height` value. Auto=nil/inherit font leading, Percent=value/100 multiplier, Px=absolute pixels. Update `frontend/text-editor/src/editor/content/dom/Style.js` normalization to handle all three modes. Add a mode toggle UI in `typography.cljs` `spacing-options*` (`typography.cljs:586-611`). Update render-wasm `text.rs` line-height computation.

## 15. Paragraph spacing + paragraph indentation  `[P1 / S / absent]`
**Figma behavior.** Pixel distance between Enter-separated paragraphs within one text node; saved in text styles. Paragraph indentation offsets first line of a paragraph (left-aligned only); saved in text styles.
**Implementation hint.** `paragraph-spacing` exists ONLY in the token import schema (`common/src/app/common/files/tokens.cljc:85`) but NOT as a native shape attribute. Add `:paragraph-spacing` (px) and `:paragraph-indent` (px) to `text-spacing-attrs` in `text.cljc` (currently only `[:line-height :letter-spacing]`). Add to paragraph-level schema in `shape/text.cljc`. Add UI controls in `typography.cljs` `spacing-options*`. Renderer (render-wasm `text.rs`) must apply paragraph spacing between paragraphs and indent on first line. `TextSpan.js`/`Paragraph.js` must emit CSS margin-between / `text-indent`.

## 16. List styles (bulleted / numbered lists + list spacing + hanging lists)  `[P1 / L / absent]`
**Figma behavior.** Bulleted (unordered) and numbered (ordered) lists rendered inside a text node, with list spacing (px between items) and hanging lists (markers outside bounding box).
**Implementation hint.** Content model node-types are only `{root, paragraph-set, paragraph}` (`shape/text.cljc:16`) — no list/list-item nodes. Add `:list-type [:none :bulleted :numbered]` and `:list-spacing` to paragraph attrs in `shape/text.cljc`. Extend the text editor content model (`frontend/text-editor/src/editor/content`) to support list nodes or paragraph-level list markers. Renderer (render-wasm `text.rs`) must render bullet/number markers. Notable editor + renderer lift.

## 17. Text truncation / ellipsis / max-lines + explicit wrap toggle  `[P1 / M / absent]`
**Figma behavior.** Truncate adds ellipsis on overflow. Max lines limits lines before truncation (requires truncation + hug/auto resize). Auto width/height/fixed size modes. Explicit wrap on/off.
**Implementation hint.** No `text-overflow`/ellipsis/truncation exists (`text.cljc`, `TextSpan.js` STYLES have no such property). Text wrapping is implicit via grow-type only (`fixed/auto-width/auto-height` in `shape.cljc:114-117`). Add `:text-overflow [:visible :truncate]` and `:max-lines` (int) to `text-node-attrs` in `text.cljc`. Add UI in `frontend/.../sidebar/options/menus/text.cljs`. Renderer (render-wasm `text.rs` + SVG output) must clip and add ellipsis. CSS `text-overflow:ellipsis` + `-webkit-line-clamp` for max-lines.

## 18. Hyperlinks in text (per-range rich text)  `[P1 / M / absent]`
**Figma behavior.** Inline links on entire layers or selected character ranges. Shortcut Shift+Ctrl/Cmd+U; paste URL to link. Links underline by default; work in prototypes; target external sites, Figma files, pages, frames.
**Implementation hint.** No `hyperlink` field in `text-span-attrs` (`text.cljc:72-84`). Add `:hyperlink {:url string}` to `text-span-attrs`. Map to CSS in `TextSpan.js` STYLES (`text-decoration:underline` + `cursor:pointer`). Add link insertion UI in `text.cljs`/`typography.cljs`. Renderer must render underlined linked ranges. Prototype viewer could intercept link clicks for internal navigation.

## 19. Text on a path (type along any vector path)  `[P1 / L / absent]`
**Figma behavior.** Native text follows any vector path (open or closed, including boolean shapes). Blue handle sets start position; Flip toggle moves text to opposite side. Vector fill/effects transfer to text layer; fully editable; SVG vector export.
**Implementation hint.** `render-wasm/src/shapes/text_paths.rs` converts text TO vector paths (flattening for export), NOT text flowing ALONG a path. No `textPath` in the model. Add a `:text-on-path` attr referencing a path shape id + `start-offset` to the text shape schema (`shape.cljc`). Renderer must use SVG `<textPath>` or Skia path-text API to flow glyphs along the referenced path. Editor (text-editor) would need path-following cursor positioning. Significant editor + renderer work.

## 20. Advanced underline controls (style/thickness/offset/skip-ink/color + overline)  `[P1 / M / partial]`
**Figma behavior.** Underline style: solid/dotted/wavy. Underline thickness. Underline offset (distance from baseline). Skip ink (underline skips glyph descenders). Independent underline color. Overline decoration. Per-range in rich text.
**Implementation hint.** `text-decoration-options*` (`text.cljs:198-230`) only offers underline + line-through. Add `:text-decoration-style [:solid :dotted :wavy]`, `:text-decoration-thickness`, `:text-decoration-offset`, `:text-decoration-skip-ink`, `:text-decoration-color` to `text-span-attrs` in `text.cljc`. Map to CSS `text-decoration-*` properties in `TextSpan.js` STYLES. Add advanced underline controls in `text.cljs` UI. Renderer (render-wasm `text.rs`) must support these. Also add `:overline` to the decoration enum.

## 21. Gradient mesh fill (multi-point mesh gradient)  `[P1 / XL / absent]`
**Figma behavior.** Shader-fill mesh gradient with 16 editable color points in a 4×4 grid with adjustable tessellation. Stackable with other fills, savable as styles.
**Implementation hint.** No mesh-gradient schema anywhere (`color.cljc` `schema:gradient` has only start/end points + stops for linear/radial). Add `:mesh` to `gradient-types` or a new `schema:mesh-fill` with a grid of color points + tessellation. Renderer (render-wasm) must implement Coon-patch / tensor-product mesh interpolation (significant GPU work). UI needs an on-canvas mesh point editor. Large renderer lift; consider starting with a simpler 2×2 or 3×3 grid.

## 22. Video fill  `[P1 / M / absent]`
**Figma behavior.** Video + animated GIF as a fill with the same Fill/Fit/Crop/Tile modes as image fills. Paid plans only.
**Implementation hint.** Image-fill mtypes in `fills/impl.cljc:144-149` are jpeg/png/gif/webp/svg+xml only. Add video mtypes (mp4/webm). Extend `schema:image` in `color.cljc:75` to support video (or add `schema:video-fill`). Renderer must play/loop video as fill (HTML `<video>` for SVG frontend; texture upload in render-wasm). UI in `fill.cljs` must accept video upload + playback controls. Requires media-handling pipeline changes.

## 23. Image adjustments (brightness/contrast/saturation/exposure/temperature/tint/highlights/shadows/curves)  `[P1 / M / absent]`
**Figma behavior.** Per-image-fill adjustment filters: Exposure, Contrast, Saturation, Temperature, Tint, Highlights, Shadows (each −1.0 to +1.0, default 0), plus Curves. Applied to image fills only.
**Implementation hint.** `schema:image` (`color.cljc:75`) has only `width/height/mtype/id/keep-aspect-ratio` — no adjustment fields. Add `:adjustments {:exposure :contrast :saturation :temperature :tint :highlights :shadows}` (each −1..1) to `schema:image`. Apply as CSS `filter: brightness()/contrast()/saturate()/hue-rotate()` in frontend SVG rendering. render-wasm must implement GPU shader adjustments. Add adjustment UI controls in `fill.cljs` when an image fill is selected.

## 24. Image fill rotate / flip / replace  `[P1 / S / absent]`
**Figma behavior.** Rotate image fills in 90° increments or free-rotate within crop (Shift = 15°). Replace image fill via the fill swatch. Rotation affects the fill, not the object. Flip horizontal/vertical on fill.
**Implementation hint.** Only `upload-media` (insert new image) exists — no replace/swap on an existing image shape. Add `:fill-image-rotation` and `:fill-image-flip` to the image-fill schema (`color.cljc` `schema:image`). Add a replace-image operation in `frontend/.../data/workspace/` (alongside existing `upload-media`). Add rotate/flip/replace buttons in `fill.cljs` image controls. Reuse the fill transform/matrix for rotation.

## 25. Pattern fill (reference another canvas object, tiling modes)  `[P1 / L / absent]`
**Figma behavior.** Pattern fill (beta) references another canvas object with Rectangular / Horizontal-Hexagonal / Vertical-Hexagonal tiling. Stackable with other fills.
**Implementation hint.** No pattern fill in `fills.cljc` or `color.cljc`. Add `:pattern` to fill types with a referenced shape id + tiling mode `[:rectangular :horizontal-hex :vertical-hex]` + scale/offset. Renderer must tile the referenced shape's rendered output as a repeating pattern (SVG `<pattern>` for frontend; texture tiling in render-wasm). UI in `fill.cljs` to pick a source object and tiling mode.

## 26. Mask variants (vector/outline mask + luminance mask)  `[P1 / M / partial]`
**Figma behavior.** Alpha mask (opacity/alpha channel, default). Vector/outline mask (shape outline, ignoring fill/stroke translucency, crisp hard-edged clipping). Luminance mask (brightness-based visibility). Toggle mask outlines (green).
**Implementation hint.** Only one mask mode exists: a single SVG `<mask>` masked-group (`frontend/.../ui/shapes/mask.cljs`, `groups.cljs` `mask-group`). Add `:mask-mode [:alpha :vector :luminance]` to the mask-group shape schema. Vector mask = SVG `<clipPath>` (hard clip by outline, no transparency). Luminance mask = SVG `<mask>` with luminance source. Add a mask-mode select in the mask UI / context menu. Renderer must switch between `clipPath` and `mask` elements.

## 27. Outline stroke (convert stroke to filled vector path)  `[P1 / S / absent]`
**Figma behavior.** Converts a shape's stroke into a filled, editable vector path (stroke geometry as its own shape). Right-click menu / API `outlineStroke()` returns a new `VectorNode`. Required before boolean ops on stroked shapes and for SVG icon export.
**Implementation hint.** No outline-stroke op exists (only flatten/convert-to-path in `bool.cljs`). Add an outline-stroke operation in `frontend/.../data/workspace/path/shapes_to_path.cljs` that computes the stroke outline geometry (offset both sides of the path by `stroke-width/2`, handling caps/joins) and produces a filled vector-path shape. Reuse the existing path/bool geometry helpers. Add a context-menu entry in `context_menu.cljs`.

## 28. Shape builder tool (interactive merge/extract/subtract regions)  `[P1 / L / absent]`
**Figma behavior.** Combines vector layers into one shape by interactively merging (drag to combine), extracting a region to its own layer, or subtracting (Alt/Option-click). Destructive, accessed via vector edit mode secondary toolbar.
**Implementation hint.** No shape-builder anywhere. Add a `:shape-builder` mode in the vector edit toolbar (`frontend/.../workspace/shapes/path/editor.cljs`). On drag over regions, compute boolean union/subtract/extract using the existing boolean geometry engine (`common/src/app/common/geom/shapes/bool.cljc` or equivalent). Add the tool to `top_toolbar.cljs` vector-editing secondary bar. Interactive overlay on top of existing boolean ops.

## 29. Paint bucket / region fill tool (vector network flood fill)  `[P1 / M / absent]`
**Figma behavior.** Vector-network region fill (Shift+B in vector edit mode) fills bounded enclosed regions of a vector network with color — Figma's paint-bucket equivalent.
**Implementation hint.** No paint-bucket/flood-fill anywhere. Add a bucket tool in vector edit mode (`frontend/.../workspace/shapes/path/editor.cljs`) that detects enclosed regions in the vector network (graph cycle detection on the path's nodes/segments) and creates a filled sub-path for the bounded region. Reuse path topology data (`common/src/app/common/types/path.cljc`). Add to vector-edit secondary toolbar.

## 30. Variables in prototyping (conditionals, expressions, set-variable actions)  `[P1 / XL / absent]`
**Figma behavior.** Variables store object state in advanced prototypes. Set-variable action dynamically changes text content, dimensions, corner radius, auto-layout, visibility. Expressions (`+ - * /`, string concat, boolean `== != and or > < >= <=`). if/else conditionals check before performing action. Multiple actions stack unlimited on one trigger. Set-variable-mode switches themes.
**Implementation hint.** Interactions (`common/src/app/common/types/shape/interactions.cljc`) have no `:set-variable`/`:conditional`/`:set-variable-mode` action types; tokens (`data/workspace/tokens/`) are not referenced by the interaction model. Add `:set-variable`, `:set-variable-mode`, `:conditional` to `action-types`. Add an expression evaluator (small parser for `+ - * /` comparisons and/or) in `common`. Wire the viewer (`frontend/.../ui/viewer/shapes.cljs`) to evaluate expressions and conditionally dispatch actions. Bridge design tokens (`token.cljc`) as the variable store. **Big architectural lift** — new expression engine + conditional action dispatch + variable runtime in the viewer.

## 31. Variable modes (per-variable mode binding + per-frame/per-object mode resolution)  `[P1 / XL / present-but-weaker]`
**Figma behavior.** Each variable has a value per mode (light/dark, mobile/desktop). Modes resolve per-context: objects default to Auto (inherit parent's mode), can be set per frame/component/page. Switching modes updates all bound properties. Set-variable-mode prototype action switches modes on trigger.
**Implementation hint.** Penpot has theme-level switching (`tokens_lib.cljc` `ITokenThemes` `activate-theme`/`deactivate-theme`, `active-themes` is a single global set) but **no per-variable `:modes` map** and **no per-frame/per-object mode resolution**. Add a `:modes` map (mode-name→value) to the token schema (`common/src/app/common/types/token.cljc`). Add `:variable-mode` to frame/shape/page attrs for per-context mode assignment with Auto inheritance. Extend token propagation (`data/workspace/tokens/propagation.cljs`) to resolve mode per object. Add Set-variable-mode to the new prototyping action types. **XL architectural lift** extending the token system from whole-document themes to per-variable per-context modes.

## 32. Reusable effect / stroke / grid / layout-guide styles as library assets  `[P1 / L / absent]`
**Figma behavior.** Effect styles save drop shadow, inner shadow, layer blur, background blur, texture, noise configs. Stroke styles save full stroke configs. Grid styles save row/column/uniform grids. Layout-guide styles shareable via team libraries. All reusable across files.
**Implementation hint.** `file.cljc` `schema:data` has only `:colors`, `:components`, `:typographies`, `:tokens-lib` — no `:effects`/`:stroke-styles`/`:grid-styles`. Add `schema:effect-style`, `schema:stroke-style`, `schema:grid-style` to `common/src/app/common/types/` (mirroring `schema:library-color` in `color.cljc`). Add `:effect-styles`/`:stroke-styles`/`:grid-styles` to `file.cljc` `schema:data`. Add library sync (`library.cljs` `sync-colors` pattern). Add assets-panel sections (`frontend sidebar/assets.cljs` currently only has components/colors/typographies). Add apply-style UI in `shadow_row.cljs`, `stroke.cljs`, `frame_grid.cljs`. Token references in strokes/shadows/grids can point to these new style ids.

## 33. key-down + on-change event triggers  `[P1 / M / absent]`
**Figma behavior.** `on key down` fires on keyboard input. `on change` fires when an input value changes (for form inputs in prototypes).
**Implementation hint.** `event-types` (`interactions.cljc:30-36`) = `#{:click :mouse-press :mouse-over :mouse-enter :mouse-leave :after-delay}`. Add `:key-down` (with a key-code/key-filter schema) and `:on-change`. Wire viewer event listeners in `frontend/.../ui/viewer/shapes.cljs` (add `keydown` listener + `change` listener on input-shaped elements). Add trigger options in the interactions panel (`sidebar/options/menus/interactions.cljs:39-40` where `:mouse-over`/`:mouse-press` are currently commented out — re-enable those too).

## 34. Custom cubic-bezier easing for prototype transitions  `[P1 / S / absent]`
**Figma behavior.** Custom cubic-bezier easing curves for prototype animations, beyond the 5 named presets. Easing curve editor.
**Implementation hint.** `easing-types` (`interactions.cljc:56-61`) = `#{:linear :ease :ease-in :ease-out :ease-in-out}`. Add `:custom-bezier` to `easing-types` with a schema storing 4 control-point values (x1 y1 x2 y2). Pass to `dom/animate!` in `viewer/interactions.cljs` as a cubic-bezier easing function. Add a small curve editor or numeric inputs in the interactions panel `easing-options*` (`interactions.cljs:392-396`).

## 35. Device frames / prototype device presets  `[P1 / M / absent]`
**Figma behavior.** Device-frame presets (iPhone, Android, desktop, etc.) wrap prototype frames with device chrome. Preview presets for different devices.
**Implementation hint.** `schema:frame-attrs` (`shape.cljc:236-241`) has no device-frame attr. Add `:device-frame {:type :preset-name}` to frame attrs. Add a device-frame picker in the interactions panel. Viewer (`frontend/.../ui/viewer/`) must render device chrome (bezel/notch) around the frame. Could use SVG device-frame assets. Add a preset list in `common/src/app/common/types/shape/interactions.cljc` or a new `device_presets.cljc`.

## 36. In-canvas prototype preview (live play mode inside workspace)  `[P1 / L / absent]`
**Figma behavior.** Live in-canvas prototype preview overlay — play interactions directly on the workspace canvas without navigating to the separate viewer route.
**Implementation hint.** Prototype playback is a separate `/viewer` route only (`right_header.cljs:241` play button → `dcm/go-to-viewer`). `viewport/interactions.cljs` only draws static connection lines. Add an in-canvas play overlay mode in `frontend/.../ui/workspace/viewport/` that renders the selected flow's frames and dispatches interactions (reusing `viewer/shapes.cljs` interaction dispatch logic) as an overlay on the current canvas. Toggle with a play-mode button. Requires embedding the viewer interaction engine into the workspace viewport.

## 37. Scale tool (K) with scale-factor entry + anchor box  `[P1 / M / present-but-weaker]`
**Figma behavior.** Scale tool (K): click-drag bounding box, scale-multiplier dropdown, custom multiplier, enter W/H (other auto-updates). Anchor box sets which side stays fixed. Scales blurs, strokes, nested layers proportionally; ignores nested constraints.
**Implementation hint.** Penpot has resize handles + numeric W/H (`measures.cljs`) but no dedicated scale tool with scale-factor entry, anchor selection, or proportional scaling of blurs/strokes. Add a `:scale` tool mode to `top_toolbar.cljs`. Implement scale transform in `frontend/.../data/workspace/transforms.cljs` (multiply all dimensions, corner radii, stroke widths, blur values, font sizes by the scale factor). Add anchor-box UI (9-way) and scale-factor input in `measures.cljs`. Shortcut `:scale` `K` in `shortcuts.cljs`.

## 38. Branching / merging (parallel design branches + merge reviews)  `[P1 / XL / absent]`
**Figma behavior.** Unlimited branches, branch reviews (side-by-side or overlay diff grouped by page), conflict resolution, merge archives branch. Version-history checkpoints on branch create/update/merge, restore previous versions, undo merge.
**Implementation hint.** No branching anywhere (version history exists as snapshots in `data/workspace/versions.cljs` but no parallel branches or merge). Major backend + collaboration feature requiring branch data model (branch file copies / deltas), diff engine, merge resolution UI, and websocket branch coordination. Would extend `file.cljc` with `:branches` and the backend with branch storage. **XL architectural lift — recommend assessing scope carefully; may be P2 for a desktop fork.**

## 39. Canvas sections (titled canvas regions grouping frames)  `[P1 / M / absent]`
**Figma behavior.** Large canvas regions with a title that group frames for navigation and organization. Sections can be collapsed, moved, and provide canvas partitioning.
**Implementation hint.** No sections concept (`page.cljc` has only `:flows`, no sections). Add a `:section` shape type or a `:sections` map to the page schema in `common/src/app/common/types/page.cljc`. Render section titles + bounds in `frontend/.../ui/workspace/viewport/`. Add a section-creation tool and section navigation in the layers panel. Sections are non-rendering organizational containers like a lighter-weight frame group.

## 40. Code Connect (map design components to real code implementations)  `[P1 / L / absent]`
**Figma behavior.** Bridges design components to real code (React/SwiftUI/Jetpack Compose/Vue). Template files using `figma.code` tagged templates. MCP server integration enhances AI code generation with real component references. Dev Mode shows accurate code snippets.
**Implementation hint.** Feature 2 (code export) generates framework code from shapes but has no Code Connect mapping design components to real codebase components. Add a `:code-connect` map to `schema:component` (`component.cljc`) storing framework→code-template mappings. Add a Code Connect authoring UI in the component panel (`sidebar/options/menus/component.cljs`). Wire the code-gen engine (`app.util.code-gen`) to emit real component references instead of generic element code when a component has Code Connect. Enhance the existing MCP tool (`data/workspace/mcp.cljs`) to expose Code Connect data to AI agents.

## 41. AI image editing (remove background, erase object, isolate object, expand image)  `[P1 / XL / absent]`
**Figma behavior.** AI tools: remove background (transparent PNG), erase object (lasso + remove), isolate object (lasso + extract/reposition with per-object effects), expand image (generative background extension). All in a new image-editing toolbar.
**Implementation hint.** Feature 3 (AI) has design generation (`ai_bar.cljs`, `ai_gen.cljs`, `design_gen.cljs`, `llm.rs`) but **no image editing AI**. Add image-editing AI actions to the AI backend (`src-tauri/src/llm.rs` or a new image-AI module). Add an image-editing toolbar/secondary bar in `frontend/.../ui/workspace/` triggered on image selection. Remove-bg: call a segmentation model, output transparent PNG. Erase/isolate: lasso selection (need lasso tool first, #51) + inpainting. Expand: outpainting. Each produces a modified image fill on the shape. Requires model integration + lasso selection infrastructure.

## 42. Expose nested instances (surface nested component properties at top level)  `[P1 / M / absent]`
**Figma behavior.** Surfaces a nested instance's component properties at the top-level instance so designers can edit everything from one place without deep-selecting. Hovering a property row highlights the corresponding object on canvas.
**Implementation hint.** Depends on the typed component properties gap (#1). Once typed properties exist, add an `:exposed-nested-instances` list to `schema:component` (`component.cljc`) referencing nested instance shape-refs whose properties should be surfaced. In the component panel (`sidebar/options/menus/component.cljs`), render exposed nested properties at the top level with hover-to-highlight on canvas. Add an Expose-nested-instances configuration modal.

## 43. Variable collections (Figma-style top-level grouping container for variables)  `[P1 / M / present-but-weaker]`
**Figma behavior.** Collections group related variables (up to 5000 per collection) with modes; groups sub-organize within. Extended collections (Enterprise) inherit/override parent modes. Import/export DTCG JSON.
**Implementation hint.** Penpot's hierarchy is tokens→Sets→Themes (`tokens_lib.cljc`) with no separate 'collection' container — the file's tokens-lib is the implicit collection. To match Figma: add a `Collection` record/protocol to `tokens_lib.cljc` above Sets, or repurpose Themes as mode-bearing collections. Add collection CRUD UI in `frontend/.../ui/workspace/tokens/`. The tokens import/export (DTCG) already exists and would map naturally. Functionally Penpot covers grouping via sets+themes but not the Figma 1:1 collection+mode model.

---

# P2 — Nice-to-have gaps

## 44. Sticky notes (freeform canvas notes)  `[P2 / M / absent]`
**Figma behavior.** Sticky notes on the canvas — freeform notes attached to canvas positions (FigJam-style, but also available in Figma Design as annotations).
**Implementation hint.** No sticky-note shape type (only comment threads in `comments.cljs`). Add a `:note` shape type or `:sticky-note` to shape types in `shape.cljc` with text content + color + position. Render as a colored rectangle with text in `frontend/.../ui/shapes/`. Add a sticky-note tool to `top_toolbar.cljs`. Simpler than full FigJam; just a text-bearing colored shape on canvas.

## 45. Outline mode (wireframe-only view)  `[P2 / S / absent]`
**Figma behavior.** Toggle to render all shapes as outlines/wireframes for structure inspection.
**Implementation hint.** No outline/wireframe view mode. Add an `:outline-mode` flag to viewport state (`frontend/.../data/workspace/layout.cljs` alongside `:show-pixel-grid`). When active, render all shapes as stroked outlines only (no fills, no effects) in `frontend/.../ui/workspace/viewport/`. Add toggle in `main_menu.cljs` + `shortcuts.cljs`.

## 46. Pixel preview render mode (device-pixel rasterization)  `[P2 / S / absent]`
**Figma behavior.** Pixel preview renders the canvas at device pixels (1:1) to see exact pixel output, distinct from the pixel-grid overlay.
**Implementation hint.** Penpot has a pixel-grid overlay (`viewport/widgets.cljs` `pixel-grid*`) + color-picker loupe (`pixel_overlay.cljs`) but no pixel-preview render mode. Add a `:pixel-preview` flag to viewport state. When active, rasterize the SVG canvas at 1:1 device pixels (render to offscreen canvas, display zoomed). Add toggle in `main_menu.cljs` + `shortcuts.cljs`. Reuse the `pixel_overlay.cljs` offscreen-canvas infrastructure.

## 47. Command palette / unified quick-actions search  `[P2 / M / absent]`
**Figma behavior.** Unified command palette / quick-action search overlay for running any command, searching layers, and navigating.
**Implementation hint.** Only scoped tool palettes (`color_palette.cljs` Alt+P, `text_palette.cljs` Alt+T) and layer find/replace (`shortcuts.cljs` `:find`) exist. Add a global command-palette overlay component in `frontend/.../ui/workspace/` that indexes all menu actions, tools, and layer names. Register commands from `main_menu.cljs` actions. Add a Cmd/Ctrl+K shortcut. The re-frame event dispatch model makes this straightforward — enumerate registered events.

## 48. Spell check (in-canvas text spell checking)  `[P2 / M / absent]`
**Figma behavior.** In-canvas spell check in the user's language of choice while editing text layers.
**Implementation hint.** No spell check. Add a spell-check pass in the text editor (`frontend/text-editor/src/editor/content`) using a dictionary (e.g. bundled hunspell or a JS spellchecker lib). Underline misspelled words in the editor. Add language selection in settings. Add a toggle in `main_menu`/preferences.

## 49. Multi-edit text (edit multiple text layers simultaneously)  `[P2 / S / absent]`
**Figma behavior.** Edit the content of multiple text layers at once in one operation.
**Implementation hint.** The text editor (`frontend/text-editor/src/editor`) handles one text shape at a time. Add multi-select text editing: when multiple text shapes are selected, entering edit mode applies text changes to all selected text shapes. Wire in `frontend/.../data/workspace/` (dispatch text-content changes to all selected shape ids). Add a multi-edit-mode indicator in the text-editor toolbar.

## 50. Hanging punctuation + optical alignment  `[P2 / S / absent]`
**Figma behavior.** Hanging punctuation pulls opening quotes/bullet markers outside the text bounding box for cleaner edges. Found in Type Settings > Indentation.
**Implementation hint.** No hanging punctuation anywhere. Add `:hanging-punctuation` boolean to paragraph attrs in `text.cljc`. Renderer (render-wasm `text.rs`) must position leading punctuation/markers outside the text-box bounds. Add a toggle in `typography.cljs` indentation controls.

## 51. Lasso / freeform selection tool  `[P2 / M / absent]`
**Figma behavior.** Freehand lasso selection of objects on canvas; also vector lasso (Q) in vector edit mode for selecting multiple vector nodes.
**Implementation hint.** Only marquee box-select exists (`viewport/widgets.cljs` `selection-rect`). Add a `:lasso` tool to `top_toolbar.cljs` that captures a freehand path and selects shapes whose bounds intersect it. In vector edit mode (`shapes/path/editor.cljs`), add a Q-key lasso for multi-node selection. Reuse `snap.cljs` point-in-polygon testing. **Unblocks #41 erase/isolate.**

## 52. Brush tool + custom brushes (stretch/scatter, from any closed vector)  `[P2 / XL / absent]`
**Figma behavior.** Illustration brush with texture/color for an organic hand-painted look. Stretch brush (elongates style along stroke), Scatter brush (repeats style along stroke). Custom brushes from any closed vector layer; copy/paste across files.
**Implementation hint.** No brush tool (toolbar has `:curve` for pencil/freehand only). Add a `:brush` tool to `top_toolbar.cljs`. Brush = a vector stroke that applies a reusable brush style (a closed vector shape) along the path. Store brush definitions as a new asset type in `file.cljc`. Renderer must repeat/stretch the brush shape along the drawn path. Custom-brush creation = capture a selected closed vector as a brush. Significant renderer work for path-following shape repetition.

## 53. Variable width strokes (per-segment stroke width along a path)  `[P2 / L / absent]`
**Figma behavior.** Add variable stroke width along a path for tapered line work. Width handles on path. Per-segment width control.
**Implementation hint.** `stroke-width` is a single scalar (`shape.cljc:141`). Add per-segment width data to the path schema (`common/src/app/common/types/path.cljc`) — a width value per segment or width handles at nodes. Renderer (render-wasm + SVG) must vary stroke width along the path (SVG doesn't support variable-width natively — must convert to a filled outline path). Add width handles in the vector editor (`shapes/path/editor.cljs`).

## 54. Dynamic strokes (natural wiggle/variation)  `[P2 / M / absent]`
**Figma behavior.** Adds natural wiggle/variation to strokes for a hand-drawn feel.
**Implementation hint.** Add a `:stroke-variation`/`:dynamic-stroke` param to the stroke schema (`shape.cljc`). Renderer applies per-segment jitter to the path outline. Add a toggle + intensity slider in `stroke.cljs` UI. Could be a post-process on the rendered stroke outline.

## 55. Offset vector (expand/contract outline by offset)  `[P2 / M / absent]`
**Figma behavior.** Expand or contract the outline of a shape by an offset, producing a new offset vector path. Useful for outlines/halos.
**Implementation hint.** Add an offset-vector operation in `frontend/.../data/workspace/path/shapes_to_path.cljs`. Compute the offset curve (clipper offset or equivalent polygon-offset algorithm) at a given distance. Produce a new vector-path shape. Add to context menu / path tools. Reuse existing path geometry helpers.

## 56. Simplify vector (point-reduction algorithm)  `[P2 / S / absent]`
**Figma behavior.** Reduces the number of points along a path to clean up complex vector networks.
**Implementation hint.** Add a simplify operation in `shapes_to_path.cljs` applying Douglas-Peucker / Ramer-Douglas-Peucker point reduction to the selected path. Add to vector-edit-mode toolbar + context menu. Threshold/intensity slider in the UI.

## 57. Vector lasso selection in vector edit mode (Q)  `[P2 / M / absent]`
**Figma behavior.** Freehand marquee of vector nodes; press Q in vector edit mode; edit multiple nodes simultaneously.
**Implementation hint.** Vector editor (`shapes/path/editor.cljs`) selects nodes by click/box only. Add a Q-key freehand lasso mode that captures a freehand path and selects all nodes within it. Add multi-node transform (move/scale/rotate selected nodes together). Reuse lasso infrastructure from the canvas-level lasso gap (#51) if implemented.

## 58. Polygon + Star tools (adjustable point count, inner radius, corner rounding)  `[P2 / S / absent]`
**Figma behavior.** Polygon tool: adjustable point count, corner radius, flatten to snap bbox. Star tool: point count 3–60, inner radius, point corner rounding. Bounding box oversized to allow adding points.
**Implementation hint.** Toolbar (`top_toolbar.cljs`) has rect/circle/line/arrow but **no polygon or star tool**. Add `:polygon` and `:star` to the tool set. Create polygon/star shape creation in `frontend/.../data/workspace/drawing.cljs` (or equivalent). Add `:point-count`, `:inner-radius` (star), `:corner-radius` to the shape schema (`shape.cljc` or a new `shape/polygon.cljc`). Renderer must draw regular polygon/star paths. Add controls in the sidebar for point count/inner radius. Could also be implemented as a pre-configured path shape.

## 59. Arc / pie / ring / donut controls for ellipse (start/end angle, inner radius)  `[P2 / M / absent]`
**Figma behavior.** Ellipse tool supports an Arc tool for semi-circles, pie charts, rings, donuts. Start/end angle adjustment, inner radius for donut.
**Implementation hint.** Circle tool exists but no arc/pie/ring/donut controls. Add `:arc-start`/`:arc-end` (angle) and `:inner-radius` to the circle shape schema (`common/src/app/common/types/shape.cljc` circle attrs). Renderer (render-wasm + SVG) must render arc/pie/ring using SVG path arcs (A command) or `stroke-dasharray` for arcs. Add controls in the sidebar when a circle is selected: start/end angle sliders, inner radius. Reuse SVG arc path generation.

## 60. Progressive blur (gradient-like blur falloff)  `[P2 / L / absent]`
**Figma behavior.** Layer or background blur that progresses across an area for depth — gradient-like blur falloff with `startRadius`/`startOffset`/`endOffset`.
**Implementation hint.** `blur.cljc` (single `:layer-blur`) and `background_blur.cljc` (single `:background-blur`) have only a uniform radius. Add `:progressive?` boolean + `start-radius`/`start-offset`/`end-offset` to blur schemas. Renderer (render-wasm) must implement gradient blur (varying blur kernel across the shape). SVG frontend fallback could use masked blur regions. Significant GPU work for the falloff gradient.

## 61. Glass effect (refraction, dispersion, frost, splay)  `[P2 / XL / absent]`
**Figma behavior.** Glass effect with light angle, light intensity, refraction, depth, dispersion, frost, splay. One per layer. Not in SVG export.
**Implementation hint.** No glass effect in `blur.cljc`/`shadow.cljc`. Add a new `schema:glass-effect` in `common/src/app/common/types/shape/` (alongside `shadow.cljc`/`blur.cljc`) with refraction/dispersion/frost/splay params. Add `:glass` to the shape effects. Renderer (render-wasm) must implement a glass shader (refraction + dispersion + frost noise). Add UI in `frontend/.../sidebar/options/menus/` (new `glass_row.cljs`). Large GPU shader effort.

## 62. Noise effect (mono/duo/multi color modes, size, density)  `[P2 / L / absent]`
**Figma behavior.** Noise effect up to 2 per layer; Mono/Duo/Multi color modes, noise size (X/Y), density, color/opacity. Adds grain/tactile texture.
**Implementation hint.** Add `schema:noise-effect` in `common/src/app/common/types/shape/` with `:color-mode [:mono :duo :multi]`, `:size-x`/`:size-y`, `:density`, `:colors`. Add `:noise` vector to shape effects (`shape.cljc` alongside `:shadow`/`:blur`). Renderer (render-wasm) must generate a noise-texture overlay. Add UI in a new `noise_row.cljs`. Max 2 per shape.

## 63. Texture effect (edge distress, clip-to-shape)  `[P2 / L / absent]`
**Figma behavior.** Texture effect: size (X/Y), radius, clip-to-shape toggle. Drop shadows interact with clipped texture. One per layer.
**Implementation hint.** Add `schema:texture-effect` in `common/src/app/common/types/shape/` with `:size-x`/`:size-y`/`:radius`/`:clip-to-shape`. Add `:texture` to shape effects. Renderer (render-wasm) must apply a distress/texture overlay clipped to shape bounds, with shadow interaction. Add UI in a new `texture_row.cljs`. Requires texture/distress pattern assets.

## 64. Shader effects & fills (WebGPU procedural shaders)  `[P2 / XL / absent]`
**Figma behavior.** Shader fill (open beta): WebGPU shaders for mesh gradients, procedural textures (clouds, nebula, fractal noise, water caustics), patterns, gradient maps. Shader effects: bloom, chromatic metal, dither, halftone, hatching, lens distortion, warp, pixelate. Prompt-to-build shaders.
**Implementation hint.** No shader system. Would require a WebGPU shader pipeline in render-wasm for both fills (shader-as-paint) and effects (shader-as-effect). Add `:shader-fill` to fill types (`fills.cljc`) and `:shader-effect` to effects. Massive GPU + authoring-system lift; recommend P2/deferred. Could start with a few hardcoded shader presets (clouds, halftone) before a full custom shader editor.

## 65. Noise & texture on fills/shadows/gradients (grain layer)  `[P2 / M / absent]`
**Figma behavior.** Adds grain/tactile texture to layers, shadows, and gradients. Adjustable intensity.
**Implementation hint.** Add a `:noise`/`:grain` param to fill/gradient/shadow schemas (`fills.cljc`, `color.cljc` gradient, `shadow.cljc`) with intensity + size. Renderer overlays grain on the paint. Simpler than the standalone Noise effect — just a grain intensity per paint. Add a small grain slider in `fill.cljs`/`shadow_row.cljs`.

## 66. 3D transforms (native 3D dimension/depth)  `[P2 / XL / absent]`
**Figma behavior.** Native 3D transforms add a third dimension/depth to layers — rotate X/Y/Z, perspective, depth positioning.
**Implementation hint.** No 3D transform in shape schema (`shape.cljc` has `:transform` 2×3 matrix only). Add `:transform-3d` (rotateX/Y/Z, perspective, translateZ) to shape attrs. Renderer (render-wasm) must implement 3D projection. Frontend SVG fallback could use CSS `transform3d`. Large renderer lift for proper 3D; recommend P2/deferred.

## 67. Audio calls + cursor chat (live collaboration)  `[P2 / M / absent]`
**Figma behavior.** Audio calls (paid/education plans) + cursor chat + live captions on desktop during collaboration.
**Implementation hint.** Multiplayer cursors/presence/comments exist (`presence.cljs`, `viewport/presence.cljs`, `comments.cljs`) but no audio or cursor chat. Add WebRTC audio-call infrastructure (peer connection via the existing websocket signaling in `data/workspace/notifications.cljs`). Add cursor chat (transient text bubbles on cursor position). Significant networking + media work for a desktop fork.

## 68. Copy as SVG (right-click clipboard copy)  `[P2 / S / partial]`
**Figma behavior.** Right-click > Copy/Paste as > Copy as SVG copies any object as SVG to clipboard for pasting into other tools or code. Figma's recommended lossless transfer format.
**Implementation hint.** Penpot has SVG export (`inspect/exports.cljs`) but no right-click 'Copy as SVG' to clipboard. Add a context-menu entry in `frontend/.../ui/workspace/context_menu.cljs` that serializes the selected shape to SVG (reuse the existing SVG renderer in `ui/shapes/`) and writes to clipboard via `navigator.clipboard.writeText` or the Tauri clipboard plugin.

## 69. iOS/Swift + px/rem code export modes  `[P2 / M / partial]`
**Figma behavior.** Copy as code generates iOS/Swift and Android/XML snippets. Dev Mode inspect panel has px/rem toggle for code snippets.
**Implementation hint.** Feature 2 (`code.cljs` `markup-options`) has html/svg/react/nextjs/react-native/android-xml/winui3-xml/flutter/tailwind but **no iOS/Swift**. Add `:swift` to `markup-options` in `frontend/.../ui/inspect/code.cljs` and a Swift code-gen module in `app.util.code-gen`. Add a px/rem toggle in the Code tab UI that scales all px values by 1/16 for rem output. Extend the Tauri `write_code_zip` for Swift files.

## 70. Visual search + semantic asset search  `[P2 / L / absent]`
**Figma behavior.** Visual search finds visually similar designs across team files (text or image query). Asset search with semantic understanding.
**Implementation hint.** Feature 3 AI has design generation but no visual/asset search. Add a search index over file shapes (embeddings via the AI backend in `src-tauri/src/llm.rs`). Add a visual-search panel that queries by image or text and returns similar shapes/frames across the file (and linked libraries). Requires embedding storage + similarity search — moderate AI backend extension.

## 71. AI rename layers + AI text generation  `[P2 / M / absent]`
**Figma behavior.** AI contextually names layers. AI text generation for text content.
**Implementation hint.** Feature 3 AI has design generation (`ai_bar.cljs`) but no rename-layers or text-gen utilities. Add an AI rename action on selected layers (batch send shape metadata to `llm_generate` in `src-tauri/src/llm.rs`, receive descriptive names, apply via `dw/rename-shape`). Add AI text generation for text layers (prompt → generated copy, applied to text content). Reuse the existing AI backend conversation infrastructure.

## 72. Prototype sections (group frames into prototype sections)  `[P2 / M / absent]`
**Figma behavior.** Prototype sections group frames into sections for organization within a prototype flow.
**Implementation hint.** `page.cljc` has `:flows` (flat per-page) but no prototype sections. Add `:prototype-sections` to the page schema (`page.cljc`) grouping flow ids under named sections. Add section-grouping UI in the interactions panel (`sidebar/options/menus/interactions.cljs` where flows are listed). Add section navigation in the viewer flows menu.

## 73. Interaction-disabled flag + swap overlay + scroll-to actions  `[P2 / S / absent]`
**Figma behavior.** Per-interaction enable/disable without deleting. Swap overlay replaces one overlay frame with another reusing overlay settings. Scroll-to scrolls to any object within a top-level frame.
**Implementation hint.** Add `:disabled` boolean to `schema:generic-interaction-attrs` (`interactions.cljc:116-197`). Add `:swap-overlay` and `:scroll-to` to `action-types` (`interactions.cljc:38-44`) with schemas (swap-overlay: target overlay frame id; scroll-to: target shape id). Wire viewer dispatch (`viewer/shapes.cljs` `activate-interaction`) for swap-overlay (reuse open-overlay settings) and scroll-to (scroll viewport to target). Add UI in `interactions.cljs` panel.

## 74. Multiple/stacked layer blurs beyond one-of-each-type  `[P2 / S / present-but-weaker]`
**Figma behavior.** Multiple blur effects stackable (Penpot allows one layer-blur + one background-blur; Figma allows one of each type but effects are reorderable in a stack).
**Implementation hint.** `blur.cljs` caps count at 2 (one `:blur` + one `:background-blur`) and the schema is a single map not a vector (`shape.cljc` `:blur`, `:background-blur` are single maps). To match Figma's effect stack, convert `:blur`/`:background-blur` to a `:blurs` vector in `shape.cljc` (mirroring the `:shadow` vector pattern). Update `blur.cljs` to add/remove/reorder. Renderer applies in stack order. Lower priority since Penpot's one-of-each is often sufficient.

## 75. Named first-class variable scopes (author-controllable per-token scope)  `[P2 / M / present-but-weaker]`
**Figma behavior.** Scoping limits which properties a variable can be applied to (e.g. CORNER_RADIUS only). Set via Edit variable > Scope tab. Number/color/string scopes.
**Implementation hint.** Penpot's scope is implicit/derived (`token.cljc` `shape-type->attributes` / `appliable-attrs-for-shape`) — not an author-set `:scopes` field on a token. Add an optional `:scopes` set (e.g. `#{:corner-radius :width :height}`) to the token schema (`token.cljc`). Gate application UI in `frontend/.../ui/workspace/tokens/` to only show applicable fields matching the token's scopes. Default to all-applicable when unset (backward compatible).

## 76. Reusable export presets (saved export settings across objects)  `[P2 / S / absent]`
**Figma behavior.** Export presets are reusable settings (format, scale, suffix) that can be applied to multiple objects.
**Implementation hint.** `exports.cljs` has per-object export rows but no saved presets. Add `:export-presets` to `file.cljc` `schema:data` (array of `{name, format, scale, suffix}`). Add a presets dropdown in `frontend/.../ui/inspect/exports.cljs` to save/apply/delete presets. Store in the file data model alongside colors/components.

## 77. Dev Mode component playground + accessibility insights  `[P2 / M / absent]`
**Figma behavior.** Component playground in Dev Mode Inspect panel to experiment with component properties without changing the design. Accessibility insights: contrast checking, focus order.
**Implementation hint.** Feature 2 has a Code/Inspect tab (`frontend/.../ui/inspect/`) but no component playground or accessibility insights. Add a playground panel in `inspect/code.cljs` (or a new `inspect/playground.cljs`) that lets users toggle component properties on a preview. Add an accessibility panel computing WCAG contrast ratios from fill colors + checking focus/tab order. Reuse the existing code-gen + component panel infrastructure. **Depends on #1 for property toggles.**

## 78. Superscript / subscript (with faux fallback)  `[P2 / S / absent]`
**Figma behavior.** Positions characters as superscript or subscript. Falls back to faux (synthesized) typography when the font lacks true glyph support.
**Implementation hint.** Span STYLES (`TextSpan.js:22-37`) and `text-node-attrs` (`text.cljc`) have no `vertical-align:super/sub` or `baseline-shift`. Add `:baseline-shift [:super :sub :none]` to `text-span-attrs`. Map to CSS `vertical-align:super/sub` or `baseline-shift` in `TextSpan.js`. When the font lacks true glyphs, apply font-size reduction + baseline offset (faux). Add a toggle in `text.cljs` UI. Could be grouped with OpenType features (#12) but is separate rendering logic.

---

## Implementation order (recommended)

The synthesis ranks by priority then complexity. Within P0, do the small schema-first wins early (they unlock UI/renderer work in parallel) and front-load #1 (typed component properties) since #10, #42, #77 depend on it:

1. **Schema-first P0 batch** (no cross-cutting renderer deps): #2 corner smoothing, #3 stroke joins+miter, #5 conic/diamond gradient, #6 image crop, #7 slice tool, #4 per-side strokes.
2. **#1 typed component properties** (XL, unblocks #10/#42/#77).
3. **#8 smart selection** (viewport handles).
4. **P1 schema + UI batch**: #9 per-item blend modes, #14 line-height modes, #15 paragraph spacing/indent, #17 truncation, #18 hyperlinks, #20 advanced underline, #24 image rotate/flip/replace, #27 outline stroke, #33 key-down/on-change, #34 custom bezier, #35 device frames, #37 scale tool, #39 canvas sections, #73 interaction-disabled + swap-overlay + scroll-to, #78 super/subscript.
5. **P1 medium renderer batch**: #13 variable-font axes, #22 video fill, #23 image adjustments, #26 mask variants, #29 paint bucket, #40 code connect, #43 variable collections.
6. **P1 large batch**: #10 interactive components, #11 smart animate, #12 OpenType, #16 lists, #19 text-on-path, #25 pattern fill, #28 shape builder, #32 reusable styles, #36 in-canvas preview, #42 expose nested instances.
7. **P1 XL architectural**: #21 gradient mesh, #30 variables-in-prototyping, #31 variable modes, #38 branching, #41 AI image editing.
8. **P2 batch** in priority/complexity order, deferring XLs (#52 brush, #61 glass, #64 shaders, #66 3D) and the networking-heavy #67 audio unless explicitly prioritized.

Each implemented feature gets: schema (common types) → data event → workspace UI → renderer (frontend SVG + render-wasm) → export/dev-mode where relevant → static adversarial verify (no build).

---

## Verification plan (2-round checkup)

**Round 1 — bugs / issues / differences vs Figma.** An adversarial audit workflow over every implemented feature: per-feature skeptic tries to break the wiring (schema ↔ data ↔ UI ↔ renderer ↔ export round-trip), and a Figma-behavior comparator checks semantic differences (does our conic gradient actually sweep clockwise with multi-stop? does per-side stroke render the correct edges? does smart-animate match by layer name and tween the right props?). Fix everything found → verify workflow → confirm clean.

**Round 2 — polish.** A polish workflow over all implemented features: UX consistency with existing Penpot panels, copy/i18n keys present in `translations/en.po`, keyboard shortcuts wired in `shortcuts.cljs`, accessibility (focus, contrast), edge cases (empty selections, zero-size, negative inputs, mode switches), performance micro-opts (no re-render storms, memoized selectors), and visual smoothness (transitions, hover states). Static verify.

All verification is static (read + reason) — no builds, no image viewing — per the standing constraint.