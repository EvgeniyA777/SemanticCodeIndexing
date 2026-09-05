#!/usr/bin/env node
/*
 * Decode a SCIP protobuf index into stable, reviewable JSON.
 *
 * Uses scip-typescript's own bundled generated protobuf module
 * (`@sourcegraph/scip-typescript/dist/src/scip.js`) so no extra protobuf
 * toolchain is needed for the Stage 3 preflight. The Stage 3 provider adapter
 * will read SCIP in the JVM; this script exists only to produce the committed
 * `fixtures/provider-authority/scip/*.observed.json` reference artifact and to
 * let a reviewer diff real tool output.
 *
 * Usage: decode-scip.js <index.scip> [--scip-dist <dir>]
 */
'use strict';

const fs = require('fs');
const path = require('path');

function parseArgs(argv) {
  const args = { file: null, scipDist: null };
  for (let i = 2; i < argv.length; i++) {
    if (argv[i] === '--scip-dist') {
      args.scipDist = argv[++i];
    } else if (!args.file) {
      args.file = argv[i];
    }
  }
  return args;
}

const args = parseArgs(process.argv);
if (!args.file) {
  console.error('usage: decode-scip.js <index.scip> [--scip-dist <dir>]');
  process.exit(2);
}

const scipDist =
  args.scipDist ||
  process.env.SEMIDX_SCIP_TYPESCRIPT_DIST ||
  path.join(
    __dirname,
    '..',
    '..',
    '.scip-toolchain',
    'node_modules',
    '@sourcegraph',
    'scip-typescript',
    'dist',
    'src'
  );

let scip;
try {
  ({ scip } = require(path.join(scipDist, 'scip.js')));
} catch (err) {
  console.error(
    'cannot load bundled scip protobuf module from ' +
      scipDist +
      '\nrun scripts/setup-scip-typescript.sh first, or pass --scip-dist\n' +
      err.message
  );
  process.exit(1);
}

// SymbolRole bit flags (scip.proto). Rendered as names for readability.
const SYMBOL_ROLE = {
  Definition: 0x1,
  Import: 0x2,
  WriteAccess: 0x4,
  ReadAccess: 0x8,
  Generated: 0x10,
  Test: 0x20,
  ForwardDefinition: 0x40,
};

function roleNames(mask) {
  return Object.entries(SYMBOL_ROLE)
    .filter(([, bit]) => (mask & bit) !== 0)
    .map(([name]) => name);
}

const bytes = fs.readFileSync(args.file);
const index = scip.Index.deserialize(bytes);

const decoded = {
  metadata: index.metadata
    ? {
        version: index.metadata.version,
        tool_info: index.metadata.tool_info
          ? {
              name: index.metadata.tool_info.name,
              version: index.metadata.tool_info.version,
              arguments: index.metadata.tool_info.arguments,
            }
          : null,
        // project_root is an absolute file:// URI on the indexing machine;
        // drop it so the committed artifact is host-independent.
        text_document_encoding: index.metadata.text_document_encoding,
      }
    : null,
  documents: index.documents.map((doc) => ({
    relative_path: doc.relative_path,
    language: doc.language,
    symbols: doc.symbols.map((s) => ({
      symbol: s.symbol,
      kind: s.kind,
      display_name: s.display_name,
      enclosing_symbol: s.enclosing_symbol,
      relationships: (s.relationships || []).map((r) => ({
        symbol: r.symbol,
        is_reference: r.is_reference,
        is_implementation: r.is_implementation,
        is_type_definition: r.is_type_definition,
        is_definition: r.is_definition,
      })),
    })),
    occurrences: doc.occurrences.map((o) => ({
      range: o.range,
      symbol: o.symbol,
      symbol_roles: o.symbol_roles,
      role_names: roleNames(o.symbol_roles),
      syntax_kind: o.syntax_kind,
      enclosing_range: o.enclosing_range,
    })),
  })),
  external_symbols: (index.external_symbols || []).map((s) => ({
    symbol: s.symbol,
    kind: s.kind,
    display_name: s.display_name,
  })),
};

process.stdout.write(JSON.stringify(decoded, null, 2) + '\n');
