#!/usr/bin/env node
/*
 * Rewrite a SCIP index with a host-independent `metadata.project_root`.
 *
 * A raw `.scip` artifact embeds the absolute `file://` path of the machine that
 * produced it (e.g. `file:///Users/alice/...`). That path must not land in a
 * committed fixture. This script loads the index with scip-typescript's own
 * bundled protobuf module, clears `metadata.project_root`, and writes the
 * re-serialized bytes. Everything else (documents, symbols, occurrences) is
 * preserved exactly.
 *
 * Like `decode-scip.js`, this is a preflight/fixture aid only; the JVM SCIP
 * reader (`semidx.runtime.scip`) does not depend on it.
 *
 * Usage: scrub-scip.js <in.scip> <out.scip> [--scip-dist <dir>]
 */
'use strict';

const fs = require('fs');
const path = require('path');

function parseArgs(argv) {
  const args = { inFile: null, outFile: null, scipDist: null };
  for (let i = 2; i < argv.length; i++) {
    if (argv[i] === '--scip-dist') {
      args.scipDist = argv[++i];
    } else if (!args.inFile) {
      args.inFile = argv[i];
    } else if (!args.outFile) {
      args.outFile = argv[i];
    }
  }
  return args;
}

const args = parseArgs(process.argv);
if (!args.inFile || !args.outFile) {
  console.error('usage: scrub-scip.js <in.scip> <out.scip> [--scip-dist <dir>]');
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

const bytes = fs.readFileSync(args.inFile);
const index = scip.Index.deserialize(bytes);

if (index.metadata) {
  // Drop the absolute indexing-machine path; keep the rest of the metadata.
  index.metadata.project_root = '';
}

fs.writeFileSync(args.outFile, Buffer.from(index.serialize()));
console.error('wrote ' + args.outFile);
