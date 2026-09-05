/**
 * Protected Stage 0 fixture (plans/018).
 *
 * Re-export surface. Each re-export must normalize to the SAME
 * CanonicalFactKey as the origin definition in orders.ts, across regex,
 * tree-sitter, SCIP, and LSP spellings, while a plain alias rename
 * (normalize -> canonicalize) keeps a distinct exported symbol identity.
 */
export { createOrder } from './orders';
export { normalize as canonicalize } from './orders';
