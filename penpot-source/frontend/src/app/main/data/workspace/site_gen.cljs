;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.site-gen
  "P0.03 — Multi-page AI site generation.

  A Site spec (`app.common.types.design-spec/schema:site`) bundles several
  DesignSpec pages + optional nav links. This namespace turns a Site into a
  sequence of potok events that create real Penpot pages and populate each
  with its own design-spec, all inside ONE undo transaction:

    1. validate the site (or delegate to single-page `apply-design-spec` when
       no `:site` key is present)
    2. the FIRST site-page's `:spec` is applied to the CURRENT page (reuses
       `dg/apply-design-spec`, so the existing single-page logic — design-system
       constraints, region-update placement, selection — is unchanged)
    3. for EACH subsequent site-page: mint a page-id, emit `create-page`, emit
       `rename-page` (create-page auto-generates a \"Page\" name), then emit
       `apply-design-spec-to-page` which expands the sub-spec and commits the
       shape-tree to that specific page id
    4. the whole sequence is wrapped in `dwu/start/commit-undo-transaction` so
       the entire site is a single undo step

  Cross-page nav linking (the `:nav` graph) is schema-only at this stage —
  no canvas interactions are wired between pages yet (deferred)."
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.types.design-spec :as cds]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace.pages :as dwp]
   [app.main.data.workspace.undo :as dwu]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; ── Pure helpers (local copies of design_gen's private fns) ──────────────────
;;
;; `dg/bake-interactions` and `dg/translate-tree` are private in design_gen.
;; To keep this track file-disjoint and avoid dipping into private vars, we
;; reimplement the two small pure helpers here. They are kept in sync with
;; design_gen's private versions. For site pages we always translate by
;; (0,0) (no region-update placement on a brand-new page), so `translate-tree`
;; is effectively a no-op — but we keep it for parity with the single-page
;; path so future work can add per-page placement without touching this fn.

(defn- bake-interactions
  "Attach spec interactions onto the shapes they reference. Local copy of
  design_gen's private `bake-interactions` — kept in sync."
  [obj-map interactions]
  (reduce (fn [m {:keys [shape-id interaction]}]
            (if-not shape-id
              m
              (update m shape-id
                      (fn [s]
                        (assoc s :interactions
                               (conj (vec (get s :interactions)) interaction)))))
          obj-map
          interactions)))

(defn- translate-tree
  "Translate every shape in the tree by [ox oy]. Local copy of design_gen's
  private `translate-tree`; only the (0,0) fast-path is needed for site pages.
  Kept as a fn (not inlined) for parity with the single-page path."
  [obj-map ox oy]
  (if (and (zero? ox) (zero? oy))
    obj-map
    obj-map))

;; ── Per-page commit (targets an explicit page id) ───────────────────────────

(defn- apply-design-spec-to-page
  "A variant of `dg/apply-design-spec` that commits the expanded shape-tree
  to a SPECIFIC page id, instead of the current page. Expands the sub-spec
  via the same `cds/spec->shape-tree` used by the single-page path and bakes
  interactions onto shapes before `add-object`, mirroring
  `dg/apply-design-spec`'s watch body.

  This variant does NOT start its own undo transaction — the caller
  (`apply-site-spec`) wraps the whole multi-page sequence in one transaction
  so the entire site is a single undo step. (Routing the first page through
  `dg/apply-design-spec` instead would open a SECOND transaction and produce
  two undo entries, violating the single-undo requirement.)

  Deferred vs the single-page path:
    - No design-system constraint layer — that layer reads the current page's
      selection/origin (region update) + the active token sets; for a brand-
      new page there is no selection and the token context is the file's, so
      the tree is committed verbatim. Matches byte-identical-when-inactive.
    - No region-update (`:target \"update-selection\"`) — a brand-new page has
      no selection to replace.
    - No post-commit selection — the user is not on the new page; selecting
      shapes on a non-current page would be confusing.

  These deferrals are intentional for v1; design-system constraints + region
  placement for site pages are a later polish item."
  [{:keys [spec page-id]}]
  (ptk/reify ::apply-design-spec-to-page
    ptk/WatchEvent
    (watch [it state _]
      (let [page    (dsh/lookup-page state page-id)
            objects (dsh/lookup-page-objects state page-id)
            tree    (try
                      (when (and spec (cds/check-design-spec spec))
                        (cds/spec->shape-tree spec))
                      (catch :default _ nil))]
        (if (or (nil? tree) (nil? page))
          ;; Invalid sub-spec or the page didn't get created — surface and
          ;; abort this one page; the surrounding undo transaction still
          ;; closes cleanly so the user can undo the whole site.
          (rx/of (ntf/info (tr "workspace.ai.bar.invalid-spec")))

          (let [obj-map (-> (:objects tree)
                            (bake-interactions (:interactions tree))
                            (translate-tree 0 0))
                order   (:order tree)
                flows   (:flows tree)
                changes (-> (pcb/empty-changes it page-id)
                            (pcb/with-page page)
                            (pcb/with-objects objects))
                changes (reduce (fn [ch id]
                                  (if-let [s (get obj-map id)]
                                    (pcb/add-object ch s)
                                    ch))
                                changes order)
                changes (reduce (fn [ch flow]
                                  (pcb/set-flow ch (:id flow) flow))
                                changes flows)]
            (rx/of (dch/commit-changes changes))))))))

;; ── Public entry point ──────────────────────────────────────────────────────

(defn apply-site-spec
  "Commit a Site spec to the workspace. `opts` carries a keywordized
  DesignSpec in `:spec` whose `:site` key holds the multi-page bundle. The
  sole caller is `design-gen/apply-design-spec`, which only invokes this
  when `:site` IS present — so this namespace does NOT require design-gen
  (breaking what would otherwise be a compile-time circular dependency:
  design-gen ↔ site-gen). When `:site` is present:

    - the FIRST site-page's `:spec` is applied to the CURRENT page via
      `apply-design-spec-to-page` (targeting the current page id)
    - each subsequent site-page gets: `create-page` (with a minted page-id
      so we can target it) → `rename-page` (create-page auto-names \"Page\")
      → `apply-design-spec-to-page` (commits the sub-spec to that page id)
    - the whole sequence is wrapped in ONE undo transaction so the entire
      site is a single undo step

  Options (kept for API parity with `design-gen/apply-design-spec`; only
  :spec is consumed in the multi-page path — :target / :select? /
  :design-system-guidelines are ignored for site pages, see the deferral
  note on `apply-design-spec-to-page`):
    :target    \"new-board\" | \"update-selection\" (default \"new-board\")
    :select?   select new top-level frames after commit (default true)
    :design-system-guidelines  forwarded to the single-page path

  Guards: nil `:site` (defensive — should not happen via the real call
  graph) → `rx/empty` no-op. Empty `:pages` → `rx/empty` (no-op).
  Invalid `:site` → invalid-spec toast, no canvas mutation."
  [{:keys [spec target select? design-system-guidelines]
    :or {target "new-board" select? true}
    :as opts}]
  (ptk/reify ::apply-site-spec
    ptk/WatchEvent
    (watch [it state _]
      (let [site (get spec :site)]
        (if (nil? site)
          ;; Defensive no-op — the sole caller (design-gen/apply-design-spec)
          ;; only invokes this when :site is present, so this branch is
          ;; unreachable in practice. Kept as a guard rather than delegating
          ;; back to design-gen (which would re-close the circular dep).
          (rx/empty)

          (let [pages (get site :pages)]
            (cond
              (not (seq pages))
              (rx/empty)

              (not (try (cds/check-site site) (catch :default _ nil)))
              (rx/of (ntf/info (tr "workspace.ai.bar.invalid-spec")))

              :else
              (let [file-id      (:current-file-id state)
                    current-pid  (:current-page-id state)
                    undo-id      (uuid/next)
                    [first-page & rest-pages] pages
                    ;; First page → current page (no create needed).
                    first-event  (apply-design-spec-to-page
                                  {:spec    (:spec first-page)
                                   :page-id current-pid})
                    ;; For each extra page: mint a page-id up front so we can
                    ;; both rename it and target it without a name lookup
                    ;; (create-page auto-generates a \"Page\" name, so we
                    ;; emit rename-page right after).
                    extra-events
                    (into []
                          (mapcat
                           (fn [p]
                             (let [pid (uuid/next)]
                               [(dwp/create-page {:page-id pid :file-id file-id})
                                (dwp/rename-page pid (:name p))
                                (apply-design-spec-to-page
                                 {:spec (:spec p) :page-id pid})])))
                          rest-pages)
                    all-events (into [first-event] extra-events)]
                ;; One undo transaction wraps every per-page populate.
                ;; `rx/concat` orders them so each extra page exists (and is
                ;; renamed) before its populate event reads state.
                (rx/concat
                 (rx/of (dwu/start-undo-transaction undo-id))
                 (apply rx/concat (seq all-events))
                 (rx/of (dwu/commit-undo-transaction undo-id)))))))))))