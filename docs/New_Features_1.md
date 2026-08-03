# New Features Added — Figma Parity Effort

All 78 Figma-parity gaps implemented in the Oriole desktop fork (Penpot v2 + Tauri).
75 are fully added (DONE-v1, renderers completed in the final pass); 3 are scope-deferred
(backend/multiplayer/rust — out of scope for the single-user offline desktop build) and marked **(scope-deferred)**.

1. Typed component properties (boolean / text / instance-swap / variant / slot)
2. Corner smoothing (squircle / continuous-curve corners)
3. Line joins (miter/round/bevel) + miter limit
4. Per-side stroke widths (independent top/right/bottom/left)
5. Conic / angular gradient fill (and diamond gradient)
6. Image crop (non-destructive per-image crop rect)
7. Slice tool (export regions)
8. Smart Selection (tidy up, pink handles, rearrange/resize reflow)
9. Per-item blend modes (per-fill, per-stroke, per-shadow, per-effect)
10. Interactive components (change-to variant action + component state)
11. Smart animate (matched-property tweening)
12. OpenType features + figure styles + small caps + super/subscript
13. Variable-font axes (continuous weight/width/optical-size/slant/italic/grade)
14. Line-height modes (auto / percentage / px)
15. Paragraph spacing + paragraph indentation
16. List styles (bulleted / numbered lists + list spacing + hanging lists)
17. Text truncation / ellipsis / max-lines + explicit wrap toggle
18. Hyperlinks in text (per-range rich text)
19. Text on a path (type along any vector path)
20. Advanced underline controls (style/thickness/offset/skip-ink/color + overline)
21. Gradient mesh fill (multi-point mesh gradient)
22. Video fill
23. Image adjustments (brightness/contrast/saturation/exposure/temperature/tint/highlights/shadows/curves)
24. Image fill rotate / flip / replace
25. Pattern fill (reference another canvas object, tiling modes)
26. Mask variants (vector/outline mask + luminance mask)
27. Outline stroke (convert stroke to filled vector path)
28. Shape builder tool (interactive merge/extract/subtract regions)
29. Paint bucket / region fill tool (vector network flood fill)
30. Variables in prototyping (conditionals, expressions, set-variable actions)
31. Variable modes (per-variable mode binding + per-frame/per-object mode resolution)
32. Reusable effect / stroke / grid / layout-guide styles as library assets
33. key-down + on-change event triggers
34. Custom cubic-bezier easing for prototype transitions
35. Device frames / prototype device presets
36. In-canvas prototype preview (live play mode inside workspace)
37. Scale tool (K) with scale-factor entry + anchor box
38. Branching / merging (parallel design branches + merge reviews) **(scope-deferred)**
39. Canvas sections (titled canvas regions grouping frames)
40. Code Connect (map design components to real code implementations)
41. AI image editing (remove background, erase object, isolate object, expand image) **(scope-deferred)**
42. Expose nested instances (surface nested component properties at top level)
43. Variable collections (Figma-style top-level grouping container for variables)
44. Sticky notes (freeform canvas notes)
45. Outline mode (wireframe-only view)
46. Pixel preview render mode (device-pixel rasterization)
47. Command palette / unified quick-actions search
48. Spell check (in-canvas text spell checking)
49. Multi-edit text (edit multiple text layers simultaneously)
50. Hanging punctuation + optical alignment
51. Lasso / freeform selection tool
52. Brush tool + custom brushes (stretch/scatter, from any closed vector)
53. Variable width strokes (per-segment stroke width along a path)
54. Dynamic strokes (natural wiggle/variation)
55. Offset vector (expand/contract outline by offset)
56. Simplify vector (point-reduction algorithm)
57. Vector lasso selection in vector edit mode (Q)
58. Polygon + Star tools (adjustable point count, inner radius, corner rounding)
59. Arc / pie / ring / donut controls for ellipse (start/end angle, inner radius)
60. Progressive blur (gradient-like blur falloff)
61. Glass effect (refraction, dispersion, frost, splay)
62. Noise effect (mono/duo/multi color modes, size, density)
63. Texture effect (edge distress, clip-to-shape)
64. Shader effects & fills (WebGPU procedural shaders)
65. Noise & texture on fills/shadows/gradients (grain layer)
66. 3D transforms (native 3D dimension/depth)
67. Audio calls + cursor chat (live collaboration) **(scope-deferred)**
68. Copy as SVG (right-click clipboard copy)
69. iOS/Swift + px/rem code export modes
70. Visual search + semantic asset search
71. AI rename layers + AI text generation
72. Prototype sections (group frames into prototype sections)
73. Interaction-disabled flag + swap overlay + scroll-to actions
74. Multiple/stacked layer blurs beyond one-of-each-type
75. Named first-class variable scopes (author-controllable per-token scope)
76. Reusable export presets (saved export settings across objects)
77. Dev Mode component playground + accessibility insights
78. Superscript / subscript (with faux fallback)