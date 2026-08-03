/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC Sucursal en España SL
 */

/**
 * Feature 48 — in-canvas spell check infrastructure.
 *
 * This module provides the spell-check HOOK used by the text editor to
 * underline misspelled words in the contenteditable. It is intentionally
 * decoupled from any concrete dictionary so a dictionary can be plugged in
 * later without touching the editor.
 *
 * Status (DONE-v1 / partial): the tokenizer + range computation + the
 * editor-facing API (`setSpellCheck`, `setSpellCheckDictionary`,
 * `findMisspelledRanges`) are wired. The DEFAULT dictionary is the
 * `NullDictionary` which marks NO word as misspelled, so enabling spell
 * check today is a visual no-op (no squigglies) — the live underline
 * rendering and a real bundled dictionary (hunspell / nspell) are
 * DEFERRED under the no-build constraint (no large dictionary dep is
 * currently available in `text-editor/package.json`).
 *
 * The squiggly-underline CSS class lives in `TextEditor.css`
 * (`.spellcheck-misspelled`). When a real dictionary is provided, the
 * editor will wrap each misspelled word range in a
 * `<span class="spellcheck-misspelled">`; until then `decorateRanges`
 * returns an empty list and the DOM is untouched.
 */

const WORD_RE = /[\p{L}\p{N}']+/gu;

/**
 * A dictionary that considers every word correct. Replaced by
 * `setSpellCheckDictionary` with a real implementation
 * (`{ isMisspelled(word) -> boolean }`) once one is bundled.
 */
const NullDictionary = Object.freeze({
  isMisspelled(_word) {
    return false;
  },
});

/**
 * Tokenize a string into word tokens with their start offsets.
 *
 * @param {string} text
 * @returns {Array<{word: string, start: number, end: number}>}
 */
export function tokenize(text) {
  const tokens = [];
  if (typeof text !== "string" || text.length === 0) {
    return tokens;
  }
  for (const match of text.matchAll(WORD_RE)) {
    tokens.push({
      word: match[0],
      start: match.index,
      end: match.index + match[0].length,
    });
  }
  return tokens;
}

/**
 * Compute the misspelled word ranges for a piece of text given a
 * dictionary. Returns an empty array when the dictionary is the
 * `NullDictionary` (the default), so callers that guard rendering on a
 * non-empty result do nothing.
 *
 * @param {string} text
 * @param {{isMisspelled(word: string): boolean}} [dictionary]
 * @returns {Array<{start: number, end: number, word: string}>}
 */
export function findMisspelledRanges(text, dictionary = NullDictionary) {
  const dict = dictionary || NullDictionary;
  const ranges = [];
  for (const { word, start, end } of tokenize(text)) {
    // Skip pure-numbers and single characters — mirrors typical
    // spellchecker behavior and keeps the deferred live underline calm.
    if (word.length < 2) continue;
    if (/^[\p{N}]+$/u.test(word)) continue;
    if (dict.isMisspelled(word)) {
      ranges.push({ start, end, word });
    }
  }
  return ranges;
}

/**
 * Collect the visible text of a root element plus per-text-node offsets,
 * so misspelled ranges can be mapped back to DOM nodes. Returns
 * `{ text, nodes: [{node, start, end}] }`. Used by the editor to know
 * where to wrap misspelled words; with the NullDictionary the result is
 * unused.
 *
 * @param {HTMLElement} root
 */
export function collectText(root) {
  const nodes = [];
  const walker = document.createTreeWalker(
    root,
    NodeFilter.SHOW_TEXT,
    null,
  );
  let offset = 0;
  let text = "";
  let current = null;
  while ((current = walker.nextNode())) {
    const value = current.nodeValue || "";
    nodes.push({ node: current, start: offset, end: offset + value.length });
    text += value;
    offset += value.length;
  }
  return { text, nodes };
}

export { NullDictionary };
export default {
  tokenize,
  findMisspelledRanges,
  collectText,
  NullDictionary,
};