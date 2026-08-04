;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.command-bar
  "Data-layer plumbing for the Command Bar (P2.22).

  Holds the requested-mode atom and the `open-swap!` entry point used by
  the Cmd+Opt+R global shortcut (wired in `app.main.data.workspace.shortcuts`).
  Keeping this in a small data namespace — rather than in the UI namespace
  `app.main.ui.workspace.command-palette` — avoids a circular require
  between the shortcuts registry and the palette UI (the palette requires
  the shortcuts registry to index every command; the swap shortcut needs
  an `open-swap!` entry point).

  `requested-mode*` is read by the Command Bar UI on mount (then reset to
  :commands) so a plain Cmd+K always opens in command mode while Cmd+Opt+R
  opens in swap-instance mode."
  (:require
   [app.main.data.workspace :as dw]
   [app.main.store :as st]))

(def ^:private requested-mode* (atom :commands))

(defn requested-mode
  "The mode the next Command Bar open should start in (:commands by
  default). The Command Bar reads this once on mount."
  []
  (deref requested-mode*))

(defn reset-mode!
  "Reset the requested mode to :commands (called by the Command Bar after
  it has captured the mode on mount, so a subsequent plain open defaults
  to :commands)."
  []
  (reset! requested-mode* :commands))

(defn open-swap!
  "Open the Command Bar in swap-instance mode (wired to Cmd+Opt+R). Sets
  the requested mode, then toggles the :command-palette layout flag."
  []
  (reset! requested-mode* :swap-instance)
  (st/emit! (dw/toggle-layout-flag :command-palette)))