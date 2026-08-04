;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.seo
  "ALL_APPS_PARITY P1.23 — SEO metadata panel (page-level).

  Persists per-page SEO metadata (title, description, OG image URL,
  keywords) so the publish/export pipeline can emit `<meta>` tags.

  Storage: SEO is a page-level map under the page's own `:seo` key — NO
  shape attr is added. We persist via `pcb/mod-page page {:seo new-seo}`.
  `pcb/mod-page` currently destructures only `:name`/`:background`/
  `:pixel-grid-color`/`:pixel-grid-opacity` and drops unknown keys; the
  lead is extending `mod-page` (and `process-change :mod-page`) to pass
  arbitrary keys through, so calling it with `{:seo ...}` will persist
  once that extension lands. We do NOT edit changes_builder.cljc ourselves
  (shared file — lead batches it).

  This keeps the change off the shape schema and uses the standard
  page-update symbol + changes/undo pipeline. The lead should wire the
  publish pipeline (publish.cljs) to read `(:seo page)` and emit `<meta>`
  tags into the bundle `<head>`."
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [clojure.string :as cstr]
   [potok.v2.core :as ptk]))

(defn- merge-seo
  "Merge `updates` into `current`; a nil or blank string CLEARS the key
  so the UI can erase a field. Vector values (e.g. :keywords) are kept
  as-is when non-nil."
  [current updates]
  (reduce (fn [acc [k v]]
            (if (or (nil? v) (and (string? v) (cstr/blank? v)))
              (dissoc acc k)
              (assoc acc k v)))
          current
          (select-keys updates [:title :description :og-image :keywords])))

(defn set-page-seo
  "WatchEvent. Updates the current page's `:seo` map. Accepts a map with
  any of `:title`, `:description`, `:og-image`, `:keywords`. Nil/blank
  clears that key. Commits inside one undo transaction."
  [{:keys [title description og-image keywords] :as params}]
  (ptk/reify ::set-page-seo
    ptk/WatchEvent
    (watch [it state _]
      (let [page (dsh/lookup-page state)]
        (if (nil? page)
          (rx/empty)
          (let [current (or (:seo page) {})
                new-seo (merge-seo current {:title       title
                                            :description description
                                            :og-image    og-image
                                            :keywords    keywords})
                undo-id (js/Symbol)
                changes (-> (pcb/empty-changes it)
                            (pcb/with-page page)
                            (pcb/mod-page page {:seo new-seo}))]
            (rx/concat
             (rx/of (dwu/start-undo-transaction undo-id)
                    (dch/commit-changes changes))
             (rx/of (dwu/commit-undo-transaction undo-id)))))))))