import { assert } from 'console';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { Identity } from 'spacetimedb';
import {
  DbConnection,
  ErrorContext,
  EventContext,
} from './module_bindings/index.js';

import http from 'http';

// Configuration
const HOST = process.env.SPACETIMEDB_HOST ?? 'ws://spacetimedb:3000';
const DB_NAME = process.env.SPACETIMEDB_DB_NAME ?? 'wr-register';

// Main entry point
async function main(): Promise<void> {
  console.log(`Connecting to SpacetimeDB...`);
  console.log(`  URI: ${HOST}`);
  console.log(`  Module: ${DB_NAME}`);

  // Build and establish connection
  const conn = DbConnection.builder()
    .withUri(HOST)
    .withDatabaseName(DB_NAME)
    .withToken(loadToken())
    .onConnect(onConnect)
    .onDisconnect(onDisconnect)
    .onConnectError(onConnectError)
    .build();

  //
  // REST API for Jepsen transactions
  //

  const port = process.env.CLIENT_PORT || '3000';
  const portNumber = Number.parseInt(port);

  // TODO: put in a shared type location for SpacetimeDB server
  type F = 'r' | 'w';
  type K = number;
  type V = number | null;
  type MOP = [F, K, V,];
  type TXN = MOP[];

  const endpoint = http.createServer((req, res) => {
    // we only know how to handle POSTs to /txn
    const { method } = req;

    assert(
      (method == 'POST') && (req.url == '/txn'),
      `Invalid HTTP method / path: ${method} / ${req.url}`);

    // body is a String representing a Jepsen txn as JSON
    let body = '';
    req.on('data', chunk => {
      body += chunk.toString();
    });

    req.on('end', async () => {
      try {
        // TODO: remove debugging
        console.log(`[endpoint] request: body: "${body}"`);

        // TODO: document and work-a-round the extra parse, stringify
        const result = await conn.procedures.txn({ value: body });
        const txn: TXN = JSON.parse(result) as TXN;
        const response = JSON.stringify({ type: 'ok', value: txn });

        console.log(`[endpoint] response: "${response}"`);

        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(response);

      } catch (error: any) {
        console.log(`[endpoint] error: ${error.toString()}`);
        const response = JSON.stringify({ 'type': 'info', 'error': error.toString() });
        console.log(`[endpoint] response: "${response}"`);

        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(response);
      }
    });
  });

  endpoint.listen(portNumber, () => {
    console.log(`Jepsen endpoint running at http://localhost:${port}`);
  });
}

function onConnect(
  conn: DbConnection,
  identity: Identity,
  token: string
): void {
  console.log('\nConnected to SpacetimeDB!');
  console.log(`Identity: ${identity.toHexString().slice(0, 16)}...`);

  // Save token for future connections
  saveToken(token);

  // Subscribe to all tables
  conn
    .subscriptionBuilder()
    .onApplied(ctx => {
      // log current registers in client cache
      const registers = [...ctx.db.registers.iter()];
      console.log(`\nCurrent registers (${registers.length}):`);
      if (registers.length === 0) {
        console.log('\t', '(none yet)');
      } else {
        for (const register of registers) {
          console.log('\t', { k: register.k, v: register.v });
        }
      }

      console.log('\nPress Ctrl+C to exit');
    })
    .onError((_ctx, err) => {
      console.error('Subscription error:', err);
    })
    .subscribeToAllTables();

  // Register callbacks for table changes
  conn.db.registers.onInsert((_ctx: EventContext, register) => {
    console.log('[onInsert] ', register);
  });

  conn.db.registers.onDelete((_ctx: EventContext, register) => {
    console.log('[onDelete] ', register);
  });

  conn.db.registers.onUpdate((_ctx: EventContext, register) => {
    console.log('[onUpdate] ', register);
  });
}

function onDisconnect(_ctx: ErrorContext, error?: Error): void {
  if (error) {
    console.error('Disconnected with error:', error);
  } else {
    console.log('Disconnected from SpacetimeDB');
  }
}

function onConnectError(_ctx: ErrorContext, error: Error): void {
  console.error('Connection error:', error);
  process.exit(1);
}

// Token persistence (file-based for Node.js instead of localStorage)
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const TOKEN_FILE = path.join(__dirname, '..', '.spacetimedb-token');

function loadToken(): string | undefined {
  try {
    if (fs.existsSync(TOKEN_FILE)) {
      return fs.readFileSync(TOKEN_FILE, 'utf-8').trim();
    }
  } catch (err) {
    console.warn('Could not load token:', err);
  }
  return undefined;
}

function saveToken(token: string): void {
  try {
    fs.writeFileSync(TOKEN_FILE, token, 'utf-8');
  } catch (err) {
    console.warn('Could not save token:', err);
  }
}

main().catch(err => {
  console.error('Fatal error:', err);
  process.exit(1);
});
