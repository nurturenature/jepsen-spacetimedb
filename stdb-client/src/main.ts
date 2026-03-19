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

  // TODO: put types in a shared type location for SpacetimeDB server
  // wr-register
  type F = 'r' | 'w';
  type K = number;
  type V = number | null;
  type MOP = [F, K, V,];
  type TXN = MOP[];

  // ledger
  type ACCOUNT = number;
  type BALANCE = number;
  type ENTRY = { account: ACCOUNT, balance: BALANCE };
  type LEDGER = ENTRY[];
  type FROM = number;
  type TO = number;
  type AMOUNT = number;
  type TRANSFER = { from: FROM, to: TO, amount: AMOUNT };

  // REST API for Jepsen transactions
  // /table/f/technique
  // e.g. /registers/txn/procedure 
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
        // TODO: remove debugging
        console.log(`[endpoint] request: body: "${body}"`);

        // TODO: document and work-a-round the extra parse, stringify

        let response: string;
        switch (method! + url) {
          case "POST" + "/registers/txn/procedure":
            const result = await conn.procedures.registersTxn({ value: body });
            const txn: TXN = JSON.parse(result) as TXN;
            response = JSON.stringify({ type: 'ok', value: txn });
            break;

          case "POST" + "/ledger/read/procedure":
            const read = await conn.procedures.ledgerRead();
            const ledger: LEDGER = JSON.parse(read) as LEDGER;
            response = JSON.stringify({ type: 'ok', value: ledger });
            break;

          case "POST" + "/ledger/transfer/procedure":
            const transfer: TRANSFER = JSON.parse(body) as TRANSFER;
            await conn.procedures.ledgerTransfer(transfer);
            response = JSON.stringify({ type: 'ok', value: transfer });
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
