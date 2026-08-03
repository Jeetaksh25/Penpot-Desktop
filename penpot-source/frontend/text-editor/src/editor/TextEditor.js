/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC Sucursal en España SL
 */

import clipboard from "./clipboard/index.js";
import commands from "./commands/index.js";
import ChangeController from "./controllers/ChangeController.js";
import SelectionController from "./controllers/SelectionController.js";
import { addEventListeners, removeEventListeners } from "./Event.js";
import {
  mapContentFragmentFromHTML,
  mapContentFragmentFromString,
} from "./content/dom/Content.js";
import { resetInertElement } from "./content/dom/Style.js";
import { createRoot, createEmptyRoot } from "./content/dom/Root.js";
import { createParagraph } from "./content/dom/Paragraph.js";
import { createEmptyTextSpan, createTextSpan } from "./content/dom/TextSpan.js";
import { isLineBreak } from "./content/dom/LineBreak.js";
import LayoutType from "./layout/LayoutType.js";
import {
  findMisspelledRanges,
  collectText,
  NullDictionary,
} from "./content/SpellCheck.js";

/**
 * @typedef {Object} TextEditorOptions
 * @property {CSSStyleDeclaration|Object.<string,*>} [styleDefaults]
 * @property {SelectionControllerDebug} [debug]
 * @property {boolean} [shouldUpdatePositionOnScroll=false]
 * @property {boolean} [allowHTMLPaste=false]
 */

/**
 * Text Editor.
 */
export class TextEditor extends EventTarget {
  /**
   * Element content editable to be used by the TextEditor
   *
   * @type {HTMLElement}
   */
  #element = null;

  /**
   * Map/Dictionary of events.
   *
   * @type {Object.<string, Function>}
   */
  #events = null;

  /**
   * Root element that will contain the content.
   *
   * @type {HTMLElement}
   */
  #root = null;

  /**
   * Change controller controls when we should notify changes.
   *
   * @type {ChangeController}
   */
  #changeController = null;

  /**
   * Selection controller controls the current/saved selection.
   *
   * @type {SelectionController}
   */
  #selectionController = null;

  /**
   * Style defaults.
   *
   * @type {Object.<string, *>}
   */
  #styleDefaults = null;

  /**
   * FIXME: There is a weird case where the events
   * `beforeinput` and `input` have different `data` when
   * characters are deleted when the input type is
   * `insertCompositionText`.
   *
   * @type {boolean}
   */
  #fixInsertCompositionText = false;

  /**
   * Canvas element that renders text.
   *
   * @type {HTMLCanvasElement}
   */
  #canvas = null;

  /**
   * Text editor options.
   *
   * @type {TextEditorOptions}
   */
  #options = {};

  /**
   * A boolean indicating that this instance was
   * disposed or not.
   *
   * @type {boolean}
   */
  #isDisposed = false;

  /**
   * Feature 48 — spell check. When true the editor recomputes misspelled
   * word ranges after each input; with the default NullDictionary no
   * ranges are produced, so enabling this is a visual no-op until a real
   * dictionary is set via `setSpellCheckDictionary`. Additive: default
   * `false` keeps the existing contenteditable byte-identical.
   *
   * @type {boolean}
   */
  #spellCheckEnabled = false;

  /**
   * Pluggable dictionary `{ isMisspelled(word) -> boolean }`. Defaults to
   * the NullDictionary (marks nothing). A real dictionary (hunspell /
   * nspell) is DEFERRED under the no-build constraint.
   *
   * @type {{isMisspelled(word: string): boolean}}
   */
  #spellCheckDictionary = NullDictionary;

  /**
   * Most recent misspelled word ranges (empty with NullDictionary).
   *
   * @type {Array<{start: number, end: number, word: string}>}
   */
  #lastMisspelledRanges = [];

  /**
   * Constructor.
   *
   * @param {HTMLElement} element
   * @param {HTMLCanvasElement} canvas
   * @param {TextEditorOptions} [options]
   */
  constructor(element, canvas, options) {
    super();
    if (!(element instanceof HTMLElement)) {
      throw new TypeError("Invalid text editor element");
    }
    this.#element = element;
    this.#canvas = canvas;
    this.#events = {
      blur: this.#onBlur,
      focus: this.#onFocus,

      paste: this.#onPaste,
      cut: this.#onCut,
      copy: this.#onCopy,

      keydown: this.#onKeyDown,
      beforeinput: this.#onBeforeInput,
      input: this.#onInput,
    };
    this.#styleDefaults = options?.styleDefaults;
    this.#options = options;
    this.#setup(options);
  }

  /**
   * Setups editor properties.
   */
  #setupElementProperties(options) {
    if (!this.#element.isContentEditable) {
      this.#element.contentEditable = "true";
      // In `jsdom` it isn't enough to set the attribute 'contentEditable'
      // to `true` to work.
      // FIXME: Remove this when `jsdom` implements this interface.
      if (!this.#element.isContentEditable) {
        this.#element.setAttribute("contenteditable", "true");
      }
    }
    if (this.#element.spellcheck) this.#element.spellcheck = false;
    if (this.#element.autocapitalize) this.#element.autocapitalize = false;
    if (!this.#element.autofocus) this.#element.autofocus = true;
    if (!this.#element.role || this.#element.role !== "textbox")
      this.#element.role = "textbox";
    if (this.#element.ariaAutoComplete) this.#element.ariaAutoComplete = false;
    if (!this.#element.ariaMultiLine) this.#element.ariaMultiLine = true;
    this.#element.dataset.itype = "editor";
    if (options?.shouldUpdatePositionOnScroll) {
      this.#updatePositionFromCanvas();
    }
  }

  /**
   * Setups the root element.
   *
   * @param {TextEditorOptions} options
   */
  #setupRoot(options) {
    this.#root = createEmptyRoot(this.#styleDefaults);
    this.#element.appendChild(this.#root);
  }

  /**
   * Setups event listeners.
   *
   * @param {TextEditorOptions} options
   */
  #setupListeners(options) {
    this.#changeController.addEventListener("change", this.#onChange);
    this.#selectionController.addEventListener(
      "stylechange",
      this.#onStyleChange,
    );
    if (options?.shouldUpdatePositionOnScroll) {
      window.addEventListener("scroll", this.#onScroll);
    }
    addEventListeners(this.#element, this.#events, {
      capture: true,
    });
  }

  /**
   * Feature 48 — spell-check pass + live squiggly-underline decorator.
   *
   * Collects the visible text of the root, computes misspelled word
   * ranges with the current dictionary, and stores them on
   * `#lastMisspelledRanges`. When at least one range is produced, each
   * range's text node is wrapped in a
   * `<span class="spellcheck-misspelled">` (the CSS class in
   * `TextEditor.css` paints the wavy red underline) and the caret
   * selection is saved before and restored after the DOM mutation.
   *
   * GUARD / core invariant: with the default `NullDictionary`
   * `findMisspelledRanges` returns an empty array, so the decorator loop
   * runs zero iterations, no `<span>` is inserted, no selection is
   * touched, and `#clearSpellCheckDecorations` unwraps nothing — the
   * contenteditable DOM is byte-for-byte identical to today. Spans are
   * inserted only when a real dictionary is plugged via
   * `setSpellCheckDictionary` AND spell check is enabled.
   */
  #runSpellCheck() {
    if (!this.#spellCheckEnabled || this.#isDisposed) {
      this.#lastMisspelledRanges = [];
      return;
    }
    // Clear any existing squiggly-underline spans first so ranges
    // recompute against clean, normalized text. With NullDictionary
    // there are never spans to clear, so this is a no-op.
    this.#clearSpellCheckDecorations();
    const { text, nodes } = collectText(this.#root);
    this.#lastMisspelledRanges = findMisspelledRanges(
      text,
      this.#spellCheckDictionary,
    );
    // GUARD: empty ranges (NullDictionary / no misspellings) -> the
    // decorator runs zero iterations -> DOM untouched -> byte-identical.
    if (this.#lastMisspelledRanges.length === 0) {
      return;
    }
    this.#applySpellCheckDecorations(this.#lastMisspelledRanges, nodes);
  }

  /**
   * Removes every existing `<span class="spellcheck-misspelled">` by
   * unwrapping it (re-parenting its text node back into the span's
   * parent) and then `normalize()`s the parent so adjacent text nodes
   * merge back into one — this lets `collectText` see clean text on the
   * next `#runSpellCheck` pass. Additive: with the NullDictionary the
   * `querySelectorAll` matches zero nodes, so nothing happens.
   */
  #clearSpellCheckDecorations() {
    if (!this.#root) return;
    const spans = this.#root.querySelectorAll(".spellcheck-misspelled");
    for (const span of spans) {
      const parent = span.parentNode;
      if (!parent) continue;
      while (span.firstChild) {
        parent.insertBefore(span.firstChild, span);
      }
      parent.removeChild(span);
      parent.normalize();
    }
  }

  /**
   * Wraps each misspelled range's text segment in a
   * `<span class="spellcheck-misspelled">`. Pure DOM manipulation: for
   * each range it locates the text node (via the `collectText` node map
   * captured before any split), splits that text node at the range's
   * end then start offset with `splitText`, and wraps the isolated
   * middle text node in a span. The caret selection is saved as global
   * character offsets before the splits and restored afterward by
   * walking the (now split) text nodes back to the same offsets, so the
   * caret does not jump. Ranges are processed in descending-start order
   * so multiple ranges inside the same original text node do not
   * invalidate one another's offsets.
   *
   * @param {Array<{start: number, end: number, word: string}>} ranges
   * @param {Array<{node: Text, start: number, end: number}>} nodes
   */
  #applySpellCheckDecorations(ranges, nodes) {
    // Save the current selection as global character offsets within the
    // root text, so it can be restored after the text-node splits. Only
    // save when both anchors are text nodes inside the root; otherwise
    // leave savedSelection null and skip restoration.
    const sel = document.getSelection();
    let savedStart = null;
    let savedEnd = null;
    if (sel && sel.rangeCount > 0) {
      const r = sel.getRangeAt(0);
      if (
        r.startContainer.nodeType === Node.TEXT_NODE &&
        r.endContainer.nodeType === Node.TEXT_NODE &&
        this.#root.contains(r.startContainer) &&
        this.#root.contains(r.endContainer)
      ) {
        savedStart = this.#rootOffsetOf(r.startContainer, r.startOffset, nodes);
        savedEnd = this.#rootOffsetOf(r.endContainer, r.endOffset, nodes);
      }
    }

    // Process in descending-start order so that a split at a higher
    // offset within a text node keeps the lower range valid for the
    // subsequent (lower-offset) iteration against the same node.
    const ordered = ranges.slice().sort((a, b) => b.start - a.start);
    for (const { start, end } of ordered) {
      const entry = nodes.find(
        (e) => start >= e.start && end <= e.end && e.node.parentNode,
      );
      if (!entry) continue;
      const node = entry.node;
      const len = (node.nodeValue || "").length;
      const localStart = start - entry.start;
      const localEnd = end - entry.start;
      if (localStart < 0 || localEnd > len || localStart >= localEnd) {
        continue;
      }
      // Isolate the localStart..localEnd segment into its own text node
      // `mid`. splitText(offset) keeps the 0..offset prefix on `node`
      // and returns the new node holding the offset..end suffix.
      const after = node.splitText(localEnd);
      const mid = node.splitText(localStart);
      const span = document.createElement("span");
      span.className = "spellcheck-misspelled";
      const parent = mid.parentNode;
      if (!parent) continue;
      parent.insertBefore(span, mid);
      span.appendChild(mid);
      // `after` is left as a sibling text node; it will be merged back
      // by normalize() on the next #clearSpellCheckDecorations pass.
      void after;
    }

    // Restore the selection at the saved global offsets by walking the
    // post-split text nodes. Only restore when we actually saved.
    if (savedStart !== null && savedEnd !== null) {
      this.#setRootOffsetSelection(savedStart, savedEnd);
    }
  }

  /**
   * Computes the global character offset of `(container, offset)` within
   * the root text, using the `collectText` node map captured before any
   * split. Falls back to walking live text nodes if the container is not
   * found in the map (defensive).
   *
   * @param {Node} container
   * @param {number} offset
   * @param {Array<{node: Text, start: number, end: number}>} nodes
   * @returns {number}
   */
  #rootOffsetOf(container, offset, nodes) {
    for (const e of nodes) {
      if (e.node === container) {
        return e.start + offset;
      }
    }
    // Fallback: walk live text nodes.
    const walker = document.createTreeWalker(
      this.#root,
      NodeFilter.SHOW_TEXT,
      null,
    );
    let acc = 0;
    let n;
    while ((n = walker.nextNode())) {
      if (n === container) return acc + offset;
      acc += (n.nodeValue || "").length;
    }
    return acc;
  }

  /**
   * Sets the selection to the given global character offsets within the
   * root text by walking the (post-split) text nodes. No-op when the
   * offsets cannot be located (e.g. root empty / disposed).
   *
   * @param {number} globalStart
   * @param {number} globalEnd
   */
  #setRootOffsetSelection(globalStart, globalEnd) {
    const sel = document.getSelection();
    if (!sel) return;
    const walker = document.createTreeWalker(
      this.#root,
      NodeFilter.SHOW_TEXT,
      null,
    );
    let acc = 0;
    let n;
    let startSet = false;
    const range = document.createRange();
    while ((n = walker.nextNode())) {
      const len = (n.nodeValue || "").length;
      if (!startSet && globalStart <= acc + len) {
        range.setStart(n, Math.max(0, globalStart - acc));
        startSet = true;
      }
      if (startSet && globalEnd <= acc + len) {
        range.setEnd(n, Math.max(0, globalEnd - acc));
        break;
      }
      acc += len;
    }
    if (startSet) {
      sel.removeAllRanges();
      sel.addRange(range);
    }
  }

  /**
   * Enables or disables spell check. Additive: default `false`; enabling
   * with the NullDictionary is a visual no-op.
   *
   * @param {boolean} enabled
   * @returns {TextEditor}
   */
  setSpellCheck(enabled) {
    this.#spellCheckEnabled = Boolean(enabled);
    if (this.#spellCheckEnabled) {
      this.#runSpellCheck();
    } else {
      this.#lastMisspelledRanges = [];
    }
    return this;
  }

  /**
   * Returns whether spell check is enabled.
   *
   * @type {boolean}
   */
  get spellCheck() {
    return this.#spellCheckEnabled;
  }

  /**
   * Sets the dictionary used for spell check. Pass an object with
   * `isMisspelled(word) -> boolean`. Until this is called with a real
   * dictionary, the NullDictionary marks nothing.
   *
   * @param {{isMisspelled(word: string): boolean}} dictionary
   * @returns {TextEditor}
   */
  setSpellCheckDictionary(dictionary) {
    this.#spellCheckDictionary = dictionary || NullDictionary;
    if (this.#spellCheckEnabled) {
      this.#runSpellCheck();
    }
    return this;
  }

  /**
   * Disposes everything.
   */
  dispose() {
    if (this.#isDisposed) {
      return this;
    }
    this.#isDisposed = true;

    // Dispose change controller.
    this.#changeController.removeEventListener("change", this.#onChange);
    this.#changeController.dispose();
    this.#changeController = null;

    // Disposes selection controller.
    this.#selectionController.removeEventListener(
      "stylechange",
      this.#onStyleChange,
    );
    this.#selectionController.dispose();
    this.#selectionController = null;

    // Disposes the rest of event listeners.
    removeEventListeners(this.#element, this.#events);
    if (this.#options?.shouldUpdatePositionOnScroll) {
      window.removeEventListener("scroll", this.#onScroll);
    }

    // Disposes references to DOM elements.
    this.#element = null;
    this.#root = null;
    return this;
  }

  /**
   * Setups controllers.
   *
   * @param {TextEditorOptions} options
   */
  #setupControllers(options) {
    this.#changeController = new ChangeController(this);
    this.#selectionController = new SelectionController(
      this,
      document.getSelection(),
      options,
    );
  }

  /**
   * Setups the elements, the properties and the
   * initial content.
   */
  #setup(options) {
    this.#setupElementProperties(options);
    this.#setupRoot(options);
    this.#setupControllers(options);
    this.#setupListeners(options);
  }

  /**
   * Updates position from canvas.
   */
  #updatePositionFromCanvas() {
    const boundingClientRect = this.#canvas.getBoundingClientRect();
    this.#element.parentElement.style.top = boundingClientRect.top + "px";
    this.#element.parentElement.style.left = boundingClientRect.left + "px";
  }

  /**
   * Updates caret position using a transform object.
   *
   * @param {*} transform
   */
  updatePositionWithTransform(transform) {
    const x = transform?.x ?? 0.0;
    const y = transform?.y ?? 0.0;
    const rotation = transform?.rotation ?? 0.0;
    const scale = transform?.scale ?? 1.0;
    this.#updatePositionFromCanvas();
    this.#element.style.transformOrigin = "top left";
    this.#element.style.transform = `scale(${scale}) translate(${x}px, ${y}px) rotate(${rotation}deg)`;
  }

  /**
   * Updates caret position using viewport and shape.
   *
   * @param {Viewport} viewport
   * @param {Shape} shape
   */
  updatePositionWithViewportAndShape(viewport, shape) {
    this.updatePositionWithTransform({
      x: viewport.x + shape.selrect.x,
      y: viewport.y + shape.selrect.y,
      rotation: shape.rotation,
      scale: viewport.zoom,
    });
  }

  /**
   * Updates editor position when the page dispatches
   * a scroll event.
   *
   * @returns
   */
  #onScroll = () => this.#updatePositionFromCanvas();

  /**
   * Dispatchs a `change` event.
   *
   * @param {CustomEvent} e
   * @returns {void}
   */
  #onChange = (e) => {
    this.dispatchEvent(new e.constructor(e.type, e));
  };

  /**
   * Dispatchs a `stylechange` event.
   *
   * @param {CustomEvent} e
   * @returns {void}
   */
  #onStyleChange = (e) => {
    this.dispatchEvent(new e.constructor(e.type, e));
  };

  /**
   * On blur we create a new FakeSelection if there's any.
   *
   * @param {FocusEvent} e
   */
  #onBlur = (e) => {
    this.#changeController.notifyImmediately();
    this.#selectionController.saveSelection();
    this.dispatchEvent(new FocusEvent(e.type, e));
  };

  /**
   * On focus we should restore the FakeSelection from the current
   * selection.
   *
   * @param {FocusEvent} e
   */
  #onFocus = (e) => {
    if (!this.#selectionController.restoreSelection()) {
      this.selectAll();
    }
    this.dispatchEvent(new FocusEvent(e.type, e));
  };

  /**
   * Event called when the user pastes some text into the
   * editor.
   *
   * @param {ClipboardEvent} e
   */
  #onPaste = (e) => {
    clipboard.paste(e, this, this.#selectionController);
    this.#notifyLayout(LayoutType.FULL, null);
  };

  /**
   * Event called when the user cuts some text from the
   * editor.
   *
   * @param {ClipboardEvent} e
   */
  #onCut = (e) => clipboard.cut(e, this, this.#selectionController);

  /**
   * Event called when the user copies some text from the
   * editor.
   *
   * @param {ClipboardEvent} e
   */
  #onCopy = (e) => {
    this.dispatchEvent(
      new CustomEvent("clipboardchange", {
        detail: this.currentStyle,
      }),
    );

    clipboard.copy(e, this, this.#selectionController);
  };

  /**
   * Event called before the DOM is modified.
   *
   * @param {InputEvent} e
   */
  #onBeforeInput = (e) => {
    if (e.inputType === "historyUndo"
     || e.inputType === "historyRedo") {
      return;
    }

    if (e.inputType === "insertCompositionText" && !e.data) {
      e.preventDefault();
      this.#fixInsertCompositionText = true;
      return;
    }

    if (!(e.inputType in commands)) {
      if (e.inputType !== "insertCompositionText") {
        e.preventDefault();
      }
      return;
    }

    if (e.inputType in commands) {
      const command = commands[e.inputType];
      command(e, this, this.#selectionController);
      this.#notifyLayout(LayoutType.FULL);
    }
  };

  /**
   * Event called after the DOM is modified.
   *
   * @param {InputEvent} e
   */
  #onInput = (e) => {
    if (e.inputType === "historyUndo"
     || e.inputType === "historyRedo") {
      return;
    }

    if (
      e.inputType === "insertCompositionText" &&
      this.#fixInsertCompositionText
    ) {
      e.preventDefault();
      this.#fixInsertCompositionText = false;
      if (e.data) {
        this.#selectionController.fixInsertCompositionText();
      }
      return;
    }

    if (e.inputType === "insertCompositionText" && e.data) {
      this.#notifyLayout(LayoutType.FULL, null);
    }

    // Feature 48 — recompute misspelled ranges after input when spell
    // check is enabled. With the default NullDictionary this is a no-op
    // (no ranges, no DOM change); the live squiggly-underline rendering
    // is deferred pending a bundled dictionary (see `#runSpellCheck`).
    if (this.#spellCheckEnabled) {
      this.#runSpellCheck();
    }
  };

  /**
   * Handles keydown events
   *
   * @param {KeyboardEvent} e
   */
  #onKeyDown = (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key === "a") {
      e.preventDefault();
      this.selectAll();
    } else if ((e.ctrlKey || e.metaKey) && e.key === "Backspace") {
      e.preventDefault();
      if (this.#selectionController.isCollapsed) {
        this.#selectionController.removeWordBackward();
      } else {
        this.#selectionController.removeSelected();
      }
      this.#notifyLayout(LayoutType.FULL);
    } else if (e.shiftKey && e.key === "Enter") {
      e.preventDefault();
      if (this.#selectionController.isCollapsed) {
        this.#selectionController.insertParagraph();
      } else {
        this.#selectionController.replaceWithParagraph();
      }
      this.#notifyLayout(LayoutType.FULL);
    }
  };

  /**
   * Notifies that the edited texts needs layout.
   *
   * @param {'full'|'partial'} type
   */
  #notifyLayout(type = LayoutType.FULL) {
    this.dispatchEvent(
      new CustomEvent("needslayout", {
        detail: {
          type: type,
        },
      }),
    );
  }

  /**
   * Indicates that the TextEditor was disposed.
   *
   * @type {boolean}
   */
  get isDisposed() {
    return this.#isDisposed;
  }

  /**
   * Root element that contains all the paragraphs.
   *
   * @type {HTMLDivElement}
   */
  get root() {
    return this.#root;
  }

  set root(newRoot) {
    const previousRoot = this.#root;
    this.#root = newRoot;
    previousRoot.replaceWith(newRoot);
  }

  /**
   * Element that contains the root and that has the
   * contenteditable attribute.
   *
   * @type {HTMLElement}
   */
  get element() {
    return this.#element;
  }

  /**
   * Returns true if the content is in an empty state.
   *
   * @type {boolean}
   */
  get isEmpty() {
    return (
      this.#root.children.length === 1 &&
      this.#root.firstElementChild.children.length === 1 &&
      isLineBreak(this.#root.firstElementChild.firstElementChild.firstChild)
    );
  }

  /**
   * Indicates the amount of paragraphs in the current content.
   *
   * @type {number}
   */
  get numParagraphs() {
    return this.#root.children.length;
  }

  /**
   * CSS Style declaration for the current text span. From here we
   * can infer root, paragraph and text span declarations.
   *
   * @type {CSSStyleDeclaration}
   */
  get currentStyle() {
    return this.#selectionController.currentStyle;
  }

  /**
   * Text editor options
   *
   * @type {TextEditorOptions}
   */
  get options() {
    return this.#options;
  }

  /**
   * Focus the element
   */
  focus() {
    return this.#element.focus();
  }

  /**
   * Blurs the element
   */
  blur() {
    return this.#element.blur();
  }

  /**
   * Creates a new root.
   *
   * @param  {...any} args
   * @returns {HTMLDivElement}
   */
  createRoot(...args) {
    return createRoot(...args);
  }

  /**
   * Creates a new paragraph.
   *
   * @param  {...any} args
   * @returns {HTMLDivElement}
   */
  createParagraph(...args) {
    return createParagraph(...args);
  }

  /**
   * Creates a new text span from a string.
   *
   * @param {string} text
   * @param {Object.<string,*>|CSSStyleDeclaration} styles
   * @returns {HTMLSpanElement}
   */
  createTextSpanFromString(text, styles) {
    if (text === "") {
      return createEmptyTextSpan(styles);
    }
    return createTextSpan(new Text(text), styles);
  }

  /**
   * Creates a new text span.
   *
   * @param  {...any} args
   * @returns {HTMLSpanElement}
   */
  createTextSpan(...args) {
    return createTextSpan(...args);
  }

  /**
   * Applies the current styles to the selection or
   * the current DOM node at the caret.
   *
   * @param {Object.<string, *>} styles
   * @returns {TextEditor}
   */
  applyStylesToSelection(styles) {
    this.#selectionController.applyStyles(styles);
    this.#notifyLayout(LayoutType.FULL);
    this.#changeController.notifyImmediately();
    return this;
  }

  /**
   * Selects all content.
   *
   * @returns {TextEditor}
   */
  selectAll() {
    this.#selectionController.selectAll();
    return this;
  }

  /**
   * Moves cursor to end.
   *
   * @returns {TextEditor}
   */
  cursorToEnd() {
    this.#selectionController.cursorToEnd();
    return this;
  }
}

/**
 *
 * @param {string} html
 * @param {*} style
 * @param {boolean} allowHTMLPaste
 * @returns {Root}
 */
export function createRootFromHTML(
  html,
  style = undefined,
  allowHTMLPaste = undefined,
) {
  const fragment = mapContentFragmentFromHTML(
    html,
    style || undefined,
    allowHTMLPaste || undefined,
  );
  const root = createRoot([], style);
  root.replaceChildren(fragment);
  resetInertElement();
  return root;
}

/**
 *
 * @param {string} string
 * @returns {Root}
 */
export function createRootFromString(string) {
  const fragment = mapContentFragmentFromString(string);
  const root = createRoot([]);
  root.replaceChild(fragment);
  return root;
}

/**
 * Returns true if the passed object is a TextEditor
 * instance.
 *
 * @param {*} instance
 * @returns {boolean}
 */
export function isTextEditor(instance) {
  return instance instanceof TextEditor;
}

/**
 * Returns true if the TextEditor is empty.
 *
 * @param {TextEditor} instance
 * @returns {boolean}
 */
export function isEmpty(instance) {
  if (isTextEditor(instance)) {
    return instance.isEmpty;
  }
  throw new TypeError('Instance is not a TextEditor');
}

/**
 * Returns the root element of a TextEditor
 * instance.
 *
 * @param {TextEditor} instance
 * @returns {HTMLDivElement}
 */
export function getRoot(instance) {
  if (isTextEditor(instance)) {
    return instance.root;
  }
  return null;
}

/**
 * Sets the root of the text editor.
 *
 * @param {TextEditor} instance
 * @param {HTMLDivElement} root
 * @returns {TextEditor}
 */
export function setRoot(instance, root) {
  if (isTextEditor(instance)) {
    instance.root = root;
    return instance;
  }
  throw new TypeError("Instance is not a TextEditor");
}

/**
 * Creates a new TextEditor instance.
 *
 * @param {HTMLDivElement} element
 * @param {HTMLCanvasElement} canvas
 * @param {TextEditorOptions} options
 * @returns {TextEditor}
 */
export function create(element, canvas, options) {
  return new TextEditor(element, canvas, { ...options });
}

/**
 * Returns the current style of the TextEditor instance.
 *
 * @param {TextEditor} instance
 * @returns {CSSStyleDeclaration|undefined}
 */
export function getCurrentStyle(instance) {
  if (isTextEditor(instance)) {
    return instance.currentStyle;
  }
  throw new TypeError('Instance is not a TextEditor');
}

/**
 * Applies the specified styles to the TextEditor
 * passed.
 *
 * @param {TextEditor} instance
 * @param {Object.<string, *>} styles
 * @returns {TextEditor|null}
 */
export function applyStylesToSelection(instance, styles) {
  if (isTextEditor(instance)) {
    return instance.applyStylesToSelection(styles);
  }
  throw new TypeError('Instance is not a TextEditor');
}

/**
 * Disposes the current instance resources by nullifying
 * every property.
 *
 * @param {TextEditor} instance
 * @returns {TextEditor|null}
 */
export function dispose(instance) {
  if (isTextEditor(instance)) {
    return instance.dispose();
  }
  throw new TypeError('Instance is not a TextEditor');
}

export default TextEditor;
