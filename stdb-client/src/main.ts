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
const DB_NAME = process.env.SPACETIMEDB_DB_NAME ?? 'test-db';

// TODO: put types in a shared type location for SpacetimeDB server

// append only keyed list
type KEY = number;
type LIST = string | null;
type LISTS = LIST[];

// txn
type F = string;
type K = KEY;
type V = LIST | null;
type MOP = { f: F, k: K, v: V };
type TXN = MOP[];

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

  // REST API for Jepsen transactions
  // /table/f/technique
  // e.g. /lists/txn/procedure 
  const endpoint = http.createServer(async (req, res) => {
    const { method, url } = req;
    console.log(`[endpoint] request: ${method} ${url}`);

    // body is a JSON String representing a Jepsen request
    let body = '';
    req.on('data', chunk => {
      body += chunk.toString();
    });

    req.on('end', async () => {
      try {
        console.log(`[endpoint] request: body: ${body}`);

        let response: string;
        switch (method! + url) {
          case "POST" + "/lists/txn/procedure":
            const txn: TXN = JSON.parse(body) as TXN;
            const result = await conn.procedures.txn({ txn: txn });
            response = JSON.stringify({ type: 'ok', value: result });
            break;

          default:
            const message = `Unknown method + url: ${method} + ${url}.`;
            console.error(`[endpoint] ${message}`);
            throw new Error(message);
        }

        console.log(`[endpoint] response: "${response}"`);

        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(response);

      } catch (error: any) {
        console.log(`[endpoint] error: ${error.toString()}`);
        const response = JSON.stringify({ 'type': 'info', 'error': error.toString() });

        console.log(`[endpoint] error: response: "${response}"`);

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
      // log current lists in client cache
      const lists = [...ctx.db.lists.iter()];
      console.log(`\nCurrent lists (${lists.length}):`);
      if (lists.length === 0) {
        console.log('\t', '(none yet)');
      } else {
        for (const list of lists) {
          console.log('\t', { key: list.key, list: list.list });
        }
      }

      console.log('\nPress Ctrl+C to exit');
    })
    .onError((_ctx, err) => {
      console.error('Subscription error:', err);
    })
    .subscribeToAllTables();

  // Register callbacks for table changes
  conn.db.lists.onInsert((_ctx: EventContext, list) => {
    console.log('[onInsert] ', list);
  });

  conn.db.lists.onDelete((_ctx: EventContext, list) => {
    console.log('[onDelete] ', list);
  });

  conn.db.lists.onUpdate((_ctx: EventContext, list) => {
    console.log('[onUpdate] ', list);
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
