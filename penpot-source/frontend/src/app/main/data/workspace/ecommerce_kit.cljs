;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.ecommerce-kit
  "P1.15 — Built-in E-commerce design kit (commerce component library +
  templates).

  A curated, purely-additive e-commerce design kit the user injects from
  the Assets panel ('Add E-commerce kit'). Injection is ONE undo batch
  that commits a DesignSpec — an 'E-commerce' board carrying one group per
  commerce component (Hero Banner, Category Nav, Product Grid, Product
  Card, Cart Item, Cart Summary, Checkout Form, Order Summary) — via the
  EXISTING `design-gen/apply-design-spec` shape-creation pipeline
  (cds/spec->shape-tree → pcb/add-object). No new shape-creation path is
  invented here, mirroring `material-kit` exactly.

  The kit then stamps the file with plugin-data `:ovion \"ecommerce-kit\"`
  so a second injection is a no-op (idempotency guard).

  Byte-identical-when-inactive: this namespace is only loaded when the
  user clicks the Assets action. A file that has never been injected is
  untouched — no plugin-data, no shapes. Coral CTAs (#f28b82) match the
  Ovion accent."

  (:require
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.types.design-spec :as cds]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace.design-gen :as design-gen]
   [app.main.data.workspace.undo :as dwu]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; ── E-commerce component specs (DesignSpec frames/groups) ───────────────────
;;
;; Each commerce component is a `:group` spec whose children are the visual
;; rects + text labels. `design-gen/apply-design-spec` runs this through
;; `cds/spec->shape-tree` (the same pipeline the AI design generator and
;; the M3 kit use), so the kit reuses the EXISTING shape-creation path —
;; no new builder here.
;;
;; Coordinates are absolute within the 'E-commerce' board. The DesignSpec
;; schema has no strokes and renders `:image` specs as neutral placeholder
;; rects (see `build-image-placeholder` in design_spec.cljc), so product
;; imagery is represented as a warm placeholder rect — the same convention
;; the AI design generator uses. Coral CTAs use the Ovion accent #f28b82.

(def ^:private ec-board-width  1280)
(def ^:private ec-board-height 1520)

(def ^:private ec-coral    "#f28b82")
(def ^:private ec-dark     "#1d1b20")
(def ^:private ec-grey     "#7d7d7d")
(def ^:private ec-light    "#f4f4f4")
(def ^:private ec-border   "#e0e0e0")
(def ^:private ec-image-bg "#e8ddd8")
(def ^:private ec-white    "#ffffff")

(defn- ec-fill
  ([hex] (ec-fill hex 1))
  ([hex opacity]
   [{:fill-color hex :fill-opacity opacity}]))

(defn- ec-text
  "A text shape spec. `opts` keys: :font-family (default Inter),
  :font-size, :font-weight, :text-align, :line-height, :fills."
  [id name x y w h content opts]
  (let [{:keys [font-family font-size font-weight text-align line-height fills]
         :or {font-family "Inter"
              font-size   "14"
              font-weight "400"
              text-align  "left"
              line-height (str h)
              fills       (ec-fill ec-dark)}} opts]
    {:id          id
     :type        "text"
     :name        name
     :x           x
     :y           y
     :width       w
     :height      h
     :content     content
     :font-family font-family
     :font-size   font-size
     :font-weight font-weight
     :text-align  text-align
     :line-height line-height
     :fills       fills}))

(defn- ec-button
  "A coral CTA button group: a filled rect + a centered label."
  [id name x y w h label]
  {:id     id
   :type   "group"
   :name   name
   :x      x
   :y      y
   :width  w
   :height h
   :shapes [{:id     (str id "-bg")
             :type   "rect"
             :name   "Surface"
             :x      x
             :y      y
             :width  w
             :height h
             :r1     8 :r2 8 :r3 8 :r4 8
             :fills  (ec-fill ec-coral)}
            (ec-text (str id "-label") "Label"
                     x y w h label
                     {:font-size   (str (min 16 (Math/round (* h 0.4))))
                      :font-weight "600"
                      :text-align  "center"
                      :line-height (str h)
                      :fills       (ec-fill ec-white)})]})

(defn- ec-pill
  "A category-nav pill: a rounded rect + a centered label."
  [id x y w h label active?]
  {:id     id
   :type   "group"
   :name   "Pill"
   :x      x
   :y      y
   :width  w
   :height h
   :shapes [{:id     (str id "-bg")
             :type   "rect"
             :name   "Surface"
             :x      x
             :y      y
             :width  w
             :height h
             :r1     999 :r2 999 :r3 999 :r4 999
             :fills  (ec-fill (if active? ec-coral ec-light))}
            (ec-text (str id "-label") "Label"
                     x y w h label
                     {:font-size   "13"
                      :font-weight "500"
                      :text-align  "center"
                      :line-height (str h)
                      :fills       (ec-fill (if active? ec-white ec-dark))})]})

(defn- ec-product-card
  "Product Card: image + title + price + rating + add-to-cart button.
  `idx` is the 0-based card index used to vary the copy so a grid of
  cards reads as a real catalogue, not a stamp."
  [id x y w idx]
  (let [title  (nth ["Aurora Wireless Headphones"
                     "Linen Oversized Shirt"
                     "Ceramic Pour-Over Set"
                     "Field Canvas Tote"
                     "Walnut Desk Lamp"
                     "Merino Crew Sweater"]
                    idx "Product")
        price  (nth ["$129.00" "$58.00" "$42.00" "$36.00" "$95.00" "$74.00"]
                    idx "$0.00")
        rating (nth ["★★★★★" "★★★★☆" "★★★★☆" "★★★★★" "★★★☆☆" "★★★★☆"]
                    idx "★★★★☆")
        img-h  200
        btn-h  32]
    {:id     id
     :type   "group"
     :name   "ProductCard"
     :x      x
     :y      y
     :width  w
     :height (+ img-h 8 20 8 24 8 16 8 btn-h 16)
     :shapes [{:id     (str id "-image")
               :type   "image"
               :name   "Image"
               :x      x
               :y      y
               :width  w
               :height img-h
               :r1     12 :r2 12 :r3 12 :r4 12
               :fills  (ec-fill ec-image-bg)}
              (ec-text (str id "-title") "Title"
                       (+ x 16) (+ y img-h 8)
                       (- w 32) 20
                       title
                       {:font-size   "15"
                        :font-weight "600"
                        :line-height "20"
                        :fills       (ec-fill ec-dark)})
              (ec-text (str id "-price") "Price"
                       (+ x 16) (+ y img-h 8 20 8)
                       120 24
                       price
                       {:font-size   "18"
                        :font-weight "700"
                        :line-height "24"
                        :fills       (ec-fill ec-coral)})
              (ec-text (str id "-rating") "Rating"
                       (+ x 16) (+ y img-h 8 20 8 24 8)
                       140 16
                       rating
                       {:font-size   "14"
                        :font-weight "400"
                        :line-height "16"
                        :fills       (ec-fill "#f5a623")})
              ;; Add-to-cart button, full card width minus padding.
              (ec-button (str id "-cta") "Add to cart"
                         (+ x 16) (+ y img-h 8 20 8 24 8 16 8)
                         (- w 32) btn-h
                         "Add to cart")]}))

(defn- ec-product-grid
  "Product Grid: a group wrapping N product cards in a single row.
  Cards are equally sized with a 16px gap."
  [id x y w card-w n]
  (let [gap    16
        total  (+ (* n card-w) (* (dec n) gap))
        ;; If the requested total exceeds `w`, the cards simply overlap by
        ;; the overflow / n — the design-spec schema has no layout engine,
        ;; so we place cards at fixed absolute coords like material-kit.
        step   (if (pos? n) (/ total n) card-w)
        cards  (for [i (range n)]
                 (ec-product-card (str id "-card-" i)
                                  (+ x (* i step))
                                  y
                                  card-w
                                  i))]
    {:id     id
     :type   "group"
     :name   "ProductGrid"
     :x      x
     :y      y
     :width  total
     :height (-> cards first :height)
     :shapes (vec cards)}))

(defn- ec-hero-banner
  "Hero Banner: a full-width image placeholder + headline + subtext + CTA."
  [id x y w h]
  {:id     id
   :type   "group"
   :name   "HeroBanner"
   :x      x
   :y      y
   :width  w
   :height h
   :shapes [{:id     (str id "-image")
             :type   "image"
             :name   "Image"
             :x      x
             :y      y
             :width  w
             :height h
             :r1     16 :r2 16 :r3 16 :r4 16
             :fills  (ec-fill ec-image-bg)}
            (ec-text (str id "-headline") "Headline"
                     (+ x 48) (+ y 64)
                     (- w 96) 56
                     "Summer Sale"
                     {:font-size   "48"
                      :font-weight "700"
                      :line-height "56"
                      :fills       (ec-fill ec-dark)})
            (ec-text (str id "-subtext") "Subtext"
                     (+ x 48) (+ y 128)
                     (- w 96) 28
                     "Up to 40% off — new arrivals weekly"
                     {:font-size   "18"
                      :font-weight "400"
                      :line-height "28"
                      :fills       (ec-fill ec-grey)})
            (ec-button (str id "-cta") "Shop Now"
                       (+ x 48) (+ y 192)
                       180 48
                       "Shop Now")]})

(defn- ec-category-nav
  "Category Nav: a row of horizontal pills."
  [id x y w]
  (let [h      40
        labels ["All" "Apparel" "Accessories" "Home" "Electronics" "Sale"]
        n      (count labels)
        gap    12
        pill-w (/ (- w (* (dec n) gap)) n)]
    {:id     id
     :type   "group"
     :name   "CategoryNav"
     :x      x
     :y      y
     :width  w
     :height h
     :shapes (vec
              (for [i (range n)]
                (ec-pill (str id "-pill-" i)
                         (+ x (* i (+ pill-w gap)))
                         y
                         pill-w
                         h
                         (nth labels i)
                         (= i 0))))}))

(defn- ec-cart-item
  "Cart Item: thumbnail + title + qty + price + remove link."
  [id x y w h]
  {:id     id
   :type   "group"
   :name   "CartItem"
   :x      x
   :y      y
   :width  w
   :height h
   :shapes [{:id     (str id "-thumb")
             :type   "image"
             :name   "Thumbnail"
             :x      x
             :y      y
             :width  80
             :height h
             :r1     12 :r2 12 :r3 12 :r4 12
             :fills  (ec-fill ec-image-bg)}
            (ec-text (str id "-title") "Title"
                     (+ x 96) (+ y 6)
                     (- w 96 200) 20
                     "Aurora Wireless Headphones"
                     {:font-size   "15"
                      :font-weight "600"
                      :line-height "20"
                      :fills       (ec-fill ec-dark)})
            (ec-text (str id "-qty") "Qty"
                     (+ x 96) (+ y 32)
                     200 18
                     "Qty: 2"
                     {:font-size   "13"
                      :font-weight "400"
                      :line-height "18"
                      :fills       (ec-fill ec-grey)})
            (ec-text (str id "-price") "Price"
                     (+ x (- w 168)) (+ y 6)
                     168 28
                     "$129.00"
                     {:font-size   "18"
                      :font-weight "700"
                      :text-align  "right"
                      :line-height "28"
                      :fills       (ec-fill ec-coral)})
            (ec-text (str id "-remove") "Remove"
                     (+ x (- w 168)) (+ y 40)
                     168 18
                     "Remove"
                     {:font-size   "13"
                      :font-weight "500"
                      :text-align  "right"
                      :line-height "18"
                      :fills       (ec-fill ec-grey)})]})

(defn- ec-summary-row
  "A label + right-aligned value row used inside Cart Summary / Order Summary."
  [id x y w label value value-hex bold?]
  (let [h (if bold? 24 20)]
    [(ec-text (str id "-label") "Label"
              x y (/ w 2) h label
              {:font-size   (if bold? "16" "14")
               :font-weight (if bold? "700" "400")
               :line-height (str h)
               :fills       (ec-fill ec-dark)})
     (ec-text (str id "-value") "Value"
              (+ x (/ w 2)) y (/ w 2) h value
              {:font-size   (if bold? "16" "14")
               :font-weight (if bold? "700" "600")
               :text-align  "right"
               :line-height (str h)
               :fills       (ec-fill value-hex)})]))

(defn- ec-cart-summary
  "Cart Summary: a tinted panel with subtotal / shipping / total + checkout."
  [id x y w h]
  (let [pad 16
        row-y (+ y 56)]
    {:id     id
     :type   "group"
     :name   "CartSummary"
     :x      x
     :y      y
     :width  w
     :height h
     :shapes (vec
              (concat
               [{:id     (str id "-bg")
                 :type   "rect"
                 :name   "Panel"
                 :x      x
                 :y      y
                 :width  w
                 :height h
                 :r1     12 :r2 12 :r3 12 :r4 12
                 :fills  (ec-fill ec-light)}
                (ec-text (str id "-title") "Title"
                         (+ x pad) (+ y pad)
                         (- w (* 2 pad)) 24
                         "Cart Summary"
                         {:font-size   "16"
                          :font-weight "700"
                          :line-height "24"
                          :fills       (ec-fill ec-dark)})]
               (ec-summary-row (str id "-subtotal")
                               (+ x pad) row-y (- w (* 2 pad))
                               "Subtotal" "$129.00" ec-dark false)
               (ec-summary-row (str id "-shipping")
                               (+ x pad) (+ row-y 28) (- w (* 2 pad))
                               "Shipping" "$5.00" ec-dark false)
               (ec-summary-row (str id "-total")
                               (+ x pad) (+ row-y 64) (- w (* 2 pad))
                               "Total" "$134.00" ec-coral true)
               [(ec-button (str id "-checkout") "Checkout"
                           (+ x pad) (+ y (- h 48))
                           (- w (* 2 pad)) 32
                           "Checkout")]))}))

(defn- ec-checkout-field
  "A checkout form field: a label + a placeholder input rect."
  [id x y w label]
  {:id     id
   :type   "group"
   :name   "Field"
   :x      x
   :y      y
   :width  w
   :height 64
   :shapes [(ec-text (str id "-label") "Label"
                     x y w 18 label
                     {:font-size   "12"
                      :font-weight "500"
                      :line-height "18"
                      :fills       (ec-fill ec-grey)})
            {:id     (str id "-input")
             :type   "rect"
             :name   "Input"
             :x      x
             :y      (+ y 22)
             :width  w
             :height 36
             :r1     8 :r2 8 :r3 8 :r4 8
             :fills  (ec-fill ec-white)}]})

(defn- ec-checkout-form
  "Checkout Form: address + payment placeholder fields + place-order button."
  [id x y w h]
  (let [pad    16
        field-h 64
        gap     12
        col-w   (/ (- w (* 2 pad) gap) 2)
        ;; Row layout: row0 = Full name (full width), row1 = Email + Phone,
        ;; row2 = Address (full), row3 = City + ZIP, row4 = Card (full),
        ;; row5 = Expiry + CVC.
        rows    [{:x (+ x pad) :y (+ y 56) :w (- w (* 2 pad)) :label "Full name"}
                 {:x (+ x pad) :y (+ y 56 (* 1 (+ field-h gap))) :w col-w :label "Email"}
                 {:x (+ x pad col-w gap) :y (+ y 56 (* 1 (+ field-h gap))) :w col-w :label "Phone"}
                 {:x (+ x pad) :y (+ y 56 (* 2 (+ field-h gap))) :w (- w (* 2 pad)) :label "Shipping address"}
                 {:x (+ x pad) :y (+ y 56 (* 3 (+ field-h gap))) :w col-w :label "City"}
                 {:x (+ x pad col-w gap) :y (+ y 56 (* 3 (+ field-h gap))) :w col-w :label "ZIP code"}
                 {:x (+ x pad) :y (+ y 56 (* 4 (+ field-h gap))) :w (- w (* 2 pad)) :label "Card number"}
                 {:x (+ x pad) :y (+ y 56 (* 5 (+ field-h gap))) :w col-w :label "Expiry"}
                 {:x (+ x pad col-w gap) :y (+ y 56 (* 5 (+ field-h gap))) :w col-w :label "CVC"}]
        fields  (mapv (fn [i r]
                        (ec-checkout-field (str id "-f" i)
                                           (:x r) (:y r) (:w r) (:label r)))
                      (range) rows)]
    {:id     id
     :type   "group"
     :name   "CheckoutForm"
     :x      x
     :y      y
     :width  w
     :height h
     :shapes (vec
              (concat
               [{:id     (str id "-bg")
                 :type   "rect"
                 :name   "Panel"
                 :x      x
                 :y      y
                 :width  w
                 :height h
                 :r1     12 :r2 12 :r3 12 :r4 12
                 :fills  (ec-fill ec-white)}
                (ec-text (str id "-title") "Title"
                         (+ x pad) (+ y pad)
                         (- w (* 2 pad)) 24
                         "Checkout"
                         {:font-size   "16"
                          :font-weight "700"
                          :line-height "24"
                          :fills       (ec-fill ec-dark)})]
               fields
               [(ec-button (str id "-place-order") "Place order"
                            (+ x pad) (+ y (- h 56))
                            (- w (* 2 pad)) 40
                            "Place order")]))}))

(defn- ec-order-summary
  "Order Summary: a tinted panel with line items + total + a place-order
  button. Mirrors Cart Summary's structure."
  [id x y w h]
  (let [pad 16
        title-y (+ y pad)]
    {:id     id
     :type   "group"
     :name   "OrderSummary"
     :x      x
     :y      y
     :width  w
     :height h
     :shapes (vec
              (concat
               [{:id     (str id "-bg")
                 :type   "rect"
                 :name   "Panel"
                 :x      x
                 :y      y
                 :width  w
                 :height h
                 :r1     12 :r2 12 :r3 12 :r4 12
                 :fills  (ec-fill ec-light)}
                (ec-text (str id "-title") "Title"
                         (+ x pad) title-y
                         (- w (* 2 pad)) 24
                         "Order Summary"
                         {:font-size   "16"
                          :font-weight "700"
                          :line-height "24"
                          :fills       (ec-fill ec-dark)})]
               (ec-summary-row (str id "-item1")
                               (+ x pad) (+ title-y 32) (- w (* 2 pad))
                               "Headphones x2" "$129.00" ec-dark false)
               (ec-summary-row (str id "-item2")
                               (+ x pad) (+ title-y 32 24) (- w (* 2 pad))
                               "Linen Shirt x1" "$58.00" ec-dark false)
               (ec-summary-row (str id "-item3")
                               (+ x pad) (+ title-y 32 48) (- w (* 2 pad))
                               "Pour-Over Set x1" "$42.00" ec-dark false)
               (ec-summary-row (str id "-subtotal")
                               (+ x pad) (+ title-y 32 88) (- w (* 2 pad))
                               "Subtotal" "$229.00" ec-dark false)
               (ec-summary-row (str id "-shipping")
                               (+ x pad) (+ title-y 32 112) (- w (* 2 pad))
                               "Shipping" "$5.00" ec-dark false)
               (ec-summary-row (str id "-total")
                               (+ x pad) (+ title-y 32 148) (- w (* 2 pad))
                               "Total" "$234.00" ec-coral true)))}))

(defn- ec-design-spec
  "Build the DesignSpec for the E-commerce board. One top-level frame
  'E-commerce' containing one group per commerce component, laid out in
  readable rows (hero, nav, grid, cart row, checkout row)."
  []
  {:target "new-board"
   :frames
   [{:id     "ec-board"
     :name   "E-commerce"
     :x      0
     :y      0
     :width  ec-board-width
     :height ec-board-height
     :fills  (ec-fill ec-white)
     :shapes
     [{:id         "ec-title"
       :type       "text"
       :name       "Title"
       :x          64
       :y          32
       :width      800
       :height     32
       :content    "E-commerce Design Kit"
       :font-family "Inter"
       :font-size   "24"
       :font-weight "700"
       :text-align  "left"
       :line-height "32"
       :fills       (ec-fill ec-dark)}

      ;; Row 1 — Hero Banner (y = 96, full content width).
      (ec-hero-banner "ec-hero" 64 96 1152 300)

      ;; Row 2 — Category Nav (y = 416).
      (ec-category-nav "ec-catnav" 64 416 1152)

      ;; Row 3 — Product Grid (y = 488): 4 cards in a row.
      ;; Card width = (1152 - 3*16) / 4 = 276.
      (ec-product-grid "ec-grid" 64 488 1152 276 4)

      ;; Row 4 — Cart Item (left) + Cart Summary (right) at y = 880.
      (ec-cart-item "ec-cartitem" 64 880 720 96)
      (ec-cart-summary "ec-cartsum" 800 880 416 200)

      ;; Row 5 — Checkout Form (left) + Order Summary (right) at y = 1112.
      (ec-checkout-form "ec-checkout" 64 1112 720 376)
      (ec-order-summary "ec-ordersum" 800 1112 416 280)]}]})

;; ── Component registry (public) ─────────────────────────────────────────────
;;
;; Exposed so a future AI commerce generator / plugin can reuse the same
;; specs. Each entry is a thunk returning a fresh group spec positioned at
;; the given origin — the same shape `apply-design-spec` consumes.

(defn ecommerce-components
  "Return a map of {keyword spec-builder-fn} for every commerce component
  in the kit. Each builder fn takes [x y] (and where relevant a width) and
  returns a DesignSpec group. Public for reuse by the AI commerce pipeline
  / plugin integrations (P1.15 build direction)."
  []
  {:product-card    (fn [x y] (ec-product-card "ec-pc" x y 276 0))
   :product-grid    (fn [x y w] (ec-product-grid "ec-pg" x y w 276 4))
   :cart-item       (fn [x y] (ec-cart-item "ec-ci" x y 720 96))
   :cart-summary    (fn [x y] (ec-cart-summary "ec-cs" x y 416 200))
   :checkout-form   (fn [x y] (ec-checkout-form "ec-cf" x y 720 376))
   :order-summary   (fn [x y] (ec-order-summary "ec-os" x y 416 280))
   :category-nav    (fn [x y w] (ec-category-nav "ec-cn" x y w))
   :hero-banner     (fn [x y w] (ec-hero-banner "ec-hb" x y w 300))})

;; ── Injection event ─────────────────────────────────────────────────────────

(defn inject-ecommerce-kit
  "Add the E-commerce design kit to the current file in ONE undo
  transaction: an 'E-commerce' board of commerce components via
  `design-gen/apply-design-spec`. Idempotent — a file already stamped with
  plugin-data `:ovion \"ecommerce-kit\"` is left untouched and a friendly
  toast is shown. Nil-safe (no selected shapes / empty file are fine —
  `apply-design-spec` defaults to a new board). Purely additive — a file
  that has never been injected is byte-identical."
  []
  (ptk/reify ::inject-ecommerce-kit
    ptk/WatchEvent
    (watch [it state _]
      (let [file-data      (dsh/lookup-file-data state)
            existing-stamp (dm/get-in file-data [:plugin-data :ovion "ecommerce-kit"])
            page-id        (:current-page-id state)
            page           (dsh/lookup-page state)
            objects        (dsh/lookup-page-objects state)
            undo-id        (uuid/next)]
        (if (some? existing-stamp)
          ;; Idempotency guard — already injected, do nothing but inform.
          (rx/of (ntf/info (tr "workspace.assets.ecommerce-already-added")))

          (let [;; Plugin-data stamp changes. `with-file-data` gives the
                ;; changes builder the file context `pcb/set-plugin-data`
                ;; needs; `with-page`/`with-objects` satisfy the page slots
                ;; the nested apply-design-spec event reads.
                changes (-> (pcb/empty-changes it page-id)
                            (pcb/with-page page)
                            (pcb/with-objects objects)
                            (pcb/with-file-data file-data))
                changes (pcb/set-plugin-data changes :ovion "ecommerce-kit" "true")

                spec    (ec-design-spec)]

            (rx/of (dwu/start-undo-transaction undo-id)
                   (dch/commit-changes changes)
                   ;; Reuses the EXISTING spec→shapes pipeline. Its own
                   ;; inner start/commit undo transaction nests inside ours
                   ;; (dwu transactions accumulate while pending is
                   ;; non-empty), so the whole kit lands as one undo entry.
                   (design-gen/apply-design-spec {:spec spec :select? false})
                   (dwu/commit-undo-transaction undo-id))))))))