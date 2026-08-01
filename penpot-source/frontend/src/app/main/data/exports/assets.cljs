;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.exports.assets
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.event :as ev]
   [app.main.data.exports.wasm :as wasm.exports]
   [app.main.data.helpers :as dsh]
   [app.main.data.modal :as modal]
   [app.main.data.persistence :as dwp]
   [app.main.features :as features]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.code-gen :as cg]
   [app.util.dom :as dom]
   [app.util.websocket :as ws]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(def default-timeout 5000)

(defn toggle-detail-visibililty
  []
  (ptk/reify ::toggle-detail-visibililty
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:export :detail-visible] not))))

(defn toggle-widget-visibililty
  []
  (ptk/reify ::toggle-widget-visibility
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:export :widget-visible] not))))

(defn clear-export-state
  [id]
  (ptk/reify ::clear-export-state
    ptk/UpdateEvent
    (update [_ state]
      ;; only clear if the existing export is the same
      (let [existing-id (-> state :export :id)]
        (if (and (some? existing-id)
                 (not= id existing-id))
          state
          (dissoc state :export))))))


(defn show-workspace-export-dialog
  [{:keys [selected origin]}]
  (ptk/reify ::show-workspace-export-dialog
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id  (:current-file-id state)
            page-id  (:current-page-id state)
            selected (or selected (dsh/lookup-selected state page-id {}))

            shapes   (if (seq selected)
                       (dsh/lookup-shapes state selected)
                       (reverse (dsh/filter-shapes state #(pos? (count (:exports %))))))

            page      (dsh/lookup-page state)
            page-name (:name page)

            exports  (for [shape  shapes
                           export (:exports shape)]
                       (-> export
                           (assoc :enabled true)
                           (assoc :page-id page-id)
                           (assoc :file-id file-id)
                           (assoc :object-id (:id shape))
                           (assoc :shape (dissoc shape :exports))
                           (assoc :name (:name shape))))]

        (rx/of (modal/show :export-shapes
                           {:exports (vec exports)
                            :origin origin
                            :name page-name}))))))

(defn show-viewer-export-dialog
  [{:keys [shapes page-id file-id share-id exports name]}]
  (ptk/reify ::show-viewer-export-dialog
    ptk/WatchEvent
    (watch [_ _ _]
      (let [exports (for [shape shapes
                          export exports]
                      (-> export
                          (assoc :enabled true)
                          (assoc :page-id page-id)
                          (assoc :file-id file-id)
                          (assoc :object-id (:id shape))
                          (assoc :shape (dissoc shape :exports))
                          (assoc :name (:name shape))
                          (cond-> share-id (assoc :share-id share-id))))]
        (rx/of (modal/show :export-shapes {:exports (vec exports)
                                           :origin "viewer"
                                           :name name})))))) #_TODO

(defn show-workspace-export-frames-dialog
  [frames]
  (ptk/reify ::show-workspace-export-frames-dialog
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id   (:current-file-id state)
            page-id   (:current-page-id state)
            page      (dsh/lookup-page state)
            page-name (:name page)
            exports   (mapv (fn [frame]
                              {:enabled true
                               :page-id page-id
                               :file-id file-id
                               :object-id (:id frame)
                               :shape frame
                               :name (:name frame)})
                            frames)]

        (rx/of (modal/show :export-frames
                           {:exports exports
                            :origin "workspace:menu"
                            :name page-name}))))))

(defn- initialize-export-status
  [exports cmd resource]
  (ptk/reify ::initialize-export-status
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :export {:in-progress true
                            :resource-id (:id resource)
                            :healthy? true
                            :error false
                            :progress 0
                            :widget-visible true
                            :detail-visible true
                            :exports exports
                            :last-update (ct/now)
                            :cmd cmd}))))

(defn- update-export-status
  [{:keys [done status resource-uri filename mtype] :as data}]
  (ptk/reify ::update-export-status
    ptk/UpdateEvent
    (update [_ state]
      (let [time-diff (ct/diff-ms (get-in state [:export :last-update]) (ct/now))
            healthy?  (< time-diff 6000)]
        (cond-> state
          (= status "running")
          (update :export assoc :progress done :last-update (ct/now) :healthy? healthy?)

          (= status "error")
          (update :export assoc :in-progress false :error (:cause data) :last-update (ct/now) :healthy? healthy?)

          (= status "ended")
          (update :export assoc :in-progress false :last-update (ct/now) :healthy? healthy?))))

    ptk/WatchEvent
    (watch [_ _ _]
      (when (= status "ended")
        (dom/trigger-download-uri filename mtype resource-uri)))))

;; TODO: Remove once we support WASM SVG export
(def ^:private wasm-export-types #{:jpeg :webp :png :pdf})

(defn- wasm-export-enabled?
  "WASM export is available: the flag is set AND render-wasm is active for the
  current file. When render-wasm is inactive its shape tree isn't loaded, so a
  client-side WASM render would crash."
  [state]
  (and (contains? cf/flags :wasm-export)
       (features/active-feature? state "render-wasm/v1")))

(defn- use-wasm-export?
  "Whether to take the client-side WASM export path for `export`."
  [state export]
  (and (wasm-export-enabled? state)
       (contains? wasm-export-types (:type export))))

;; ---------------------------------------------------------------------------
;; UI-framework code export (React, Next.js, React Native, Android XML,
;; WinUI 3 XAML, Flutter). These are generated entirely on the client and
;; downloaded as a source file; they never go through the backend :export
;; RPC (which only knows png/jpeg/webp/svg/pdf).
;; ---------------------------------------------------------------------------

(defn- code-export?
  "True when `export` is a UI-framework code export target."
  [export]
  (cg/framework? (name (:type export))))

(defn- resolve-export-objects
  "Resolve the page objects map for an export spec, working in both the
  workspace state layout (`:files file-id ... :pages-index page-id`) and
  the viewer state layout (`:viewer :pages page-id`). Falls back to an
  empty map so generation degrades gracefully instead of crashing."
  [state export]
  (let [file-id (:file-id export)
        page-id (:page-id export)]
    (or (when (and file-id page-id)
          (not-empty (dsh/lookup-page-objects state file-id page-id)))
        (when page-id
          (get-in state [:viewer :pages page-id :objects]))
        {})))

(defn- export-root-shape
  "The root shape to generate code for. Prefer the original (un-truncated)
  shape looked up from `objects` so children/fills/etc. are intact; fall
  back to the shape carried by the export spec, then to a bare id map."
  [objects export]
  (or (get objects (:object-id export))
      (:shape export)
      {:id (:object-id export)}))

(defn- generate-and-download-code!
  "Generate the framework source for a single code export spec and
  trigger its browser download. Returns nil."
  [state export]
  (let [objects (resolve-export-objects state export)
        shape   (export-root-shape objects export)
        type    (name (:type export))
        code    (cg/generate-framework-code objects type [shape])]
    (cg/download-framework-code! (:name export) type code)
    nil))

(defn request-code-export
  "Event: generate and download one or more UI-framework code exports
  entirely on the client (no backend round-trip)."
  [{:keys [exports]}]
  (ptk/reify ::request-code-export
    ptk/WatchEvent
    (watch [_ state _]
      (doseq [export exports]
        (generate-and-download-code! state export))
      (rx/of (clear-export-state uuid/zero)))))

(defn request-simple-export
  [{:keys [export]}]
  (ptk/reify ::request-simple-export
    ptk/UpdateEvent
    (update [_ state]
      (cond-> state
        (and (not (use-wasm-export? state export))
             (not (code-export? export)))
        (update :export assoc :in-progress true :id uuid/zero)))

    ptk/WatchEvent
    (watch [_ state _]
      (cond
        (code-export? export)
        (do
          (generate-and-download-code! state export)
          (rx/of (clear-export-state uuid/zero)))

        (use-wasm-export? state export)
        (do
          (case (:type export)
            :pdf (wasm.exports/export-pdf export)
            (wasm.exports/export-image export))
          (rx/empty))

        :else
        (let [profile-id (:profile-id state)
              params     {:exports [export]
                          :profile-id profile-id
                          :cmd :export-shapes
                          :wait true
                          :is-wasm (wasm-export-enabled? state)}]
          (rx/concat
           (dwp/force-persist-and-wait 400)

           (->> (rp/cmd! :export params)
                (rx/map (fn [{:keys [filename mtype uri]}]
                          (dom/trigger-download-uri filename mtype uri)
                          (clear-export-state uuid/zero)))
                (rx/catch (fn [cause]
                            (rx/concat
                             (rx/of (clear-export-state uuid/zero))
                             (rx/throw cause)))))))))))

(defn request-multiple-export
  [{:keys [exports cmd name]
    :or {cmd :export-shapes}
    :as params}]
  (ptk/reify ::request-multiple-export
    ptk/WatchEvent
    (watch [_ state _]
      (let [{code-exports true asset-exports false}
            (group-by code-export? exports)]

        ;; Code exports are produced on the client. If any are present we
        ;; handle them here, then either finish (no asset exports remain)
        ;; or continue with the backend flow for the asset-only subset.
        (cond
          (and (seq code-exports) (seq asset-exports))
          (do (doseq [e code-exports] (generate-and-download-code! state e))
              (rx/of (request-multiple-export (assoc params :exports asset-exports))))

          (seq code-exports)
          (do (doseq [e code-exports] (generate-and-download-code! state e))
              (rx/of (clear-export-state uuid/zero)))

          :else
          (let [resource-id (volatile! nil)
            profile-id  (:profile-id state)
            ws-conn     (:ws-conn state)
            params      (cond->
                         {:exports exports
                          :cmd cmd
                          :profile-id profile-id
                          :force-multiple true
                          :is-wasm (wasm-export-enabled? state)}
                          (some? name)
                          (assoc :name name))

            progress-stream
            (->> (ws/get-rcv-stream ws-conn)
                 (rx/filter ws/message-event?)
                 (rx/map :payload)
                 (rx/filter #(= :export-update (:type %)))
                 (rx/filter #(= @resource-id (:resource-id %)))
                 (rx/share))

            stopper
            (rx/filter #(or (= "ended" (:status %))
                            (= "error" (:status %)))
                       progress-stream)]

        (swap! st/ongoing-tasks conj :export)

        (rx/merge
         ;; Force that all data is persisted; best effort.
         (rx/of ::dwp/force-persist)

         ;; Launch the exportation process and stores the resource id
         ;; locally.
         (->> (rp/cmd! :export params)
              (rx/map (fn [{:keys [id] :as resource}]
                        (vreset! resource-id id)
                        (initialize-export-status exports cmd resource))))

         ;; We proceed to update the export state with incoming
         ;; progress updates. We delay the stopper for give some time
         ;; to update the status with ended or errored status before
         ;; close the stream.
         (->> progress-stream
              (rx/map update-export-status)
              (rx/take-until (rx/delay 500 stopper))
              (rx/finalize (fn []
                             (swap! st/ongoing-tasks disj :export))))

         ;; We hide need to hide the ui elements of the export after
         ;; some interval. We also delay a little bit more the stopper
         ;; for ensure that after some security time, the stream is
         ;; completely closed.
         (->> progress-stream
              (rx/filter #(= "ended" (:status %)))
              (rx/take 1)
              (rx/delay default-timeout)
              (rx/map #(clear-export-state @resource-id))
              (rx/take-until (rx/delay 6000 stopper))))))))))

(defn request-export
  [{:keys [exports] :as params}]
  (let [{code-exports true asset-exports false}
        (group-by code-export? exports)]
    (cond
      ;; Mixed batch: emit the code exports and re-dispatch the
      ;; asset-only subset through the normal backend flow.
      (and (seq code-exports) (seq asset-exports))
      (ptk/reify ::request-export-mixed
        ptk/WatchEvent
        (watch [_ _ _]
          (rx/of (request-code-export {:exports code-exports})
                 (request-export (assoc params :exports asset-exports)))))

      (seq code-exports)
      (request-code-export {:exports code-exports})

      :else
      (if (= 1 (count asset-exports))
        (request-simple-export (assoc params :export (first asset-exports)))
        (request-multiple-export (assoc params :exports asset-exports))))))

(defn retry-last-export
  []
  (ptk/reify ::retry-last-export
    ptk/WatchEvent
    (watch [_ state _]
      (let [params (select-keys (:export state) [:exports :cmd])]
        (when (seq params)
          (rx/of (request-multiple-export params)))))))

(defn export-shapes-event
  [exports origin]
  (let [types (reduce (fn [counts {:keys [type]}]
                        (if (#{:png :jpeg :webp :svg :pdf} type)
                          (update counts type inc)
                          counts))
                      {:png 0, :jpeg 0, :webp 0, :pdf 0, :svg 0}
                      exports)]
    (ev/event (merge types
                     {::ev/name "export-shapes"
                      ::ev/origin origin
                      :num-shapes (count exports)}))))
