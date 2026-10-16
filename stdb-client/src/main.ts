import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import http, { Server } from 'http';

import {
  DbConnection,
  ErrorContext,
  EventContext,
} from './module_bindings/index.js';
import { DisconnectedError, Identity, UnknownCallResultError } from 'spacetimedb';

// Configuration
const SPACETIMEDB_HOST = process.env.SPACETIMEDB_HOST ?? 'ws://spacetimedb:3000';
const SPACETIMEDB_DB_NAME = process.env.SPACETIMEDB_DB_NAME ?? 'test-db';
const CONFIRMED_READS = JSON.parse(process.env.CONFIRMED_READS ?? 'true');
const AUTOMATIC_RECONNECT = JSON.parse(process.env.AUTOMATIC_RECONNECT ?? 'true');
const CLIENT_PORT = Number.parseInt(process.env.CLIENT_PORT || '3000');
const CLIENT_TIMEOUT = Number.parseInt(process.env.CLIENT_TIMEOUT || '3000');

// TODO: put types in a shared type location for SpacetimeDB server

// append only keyed list
type KEY = number;

// txn request
type F = string;
type K = KEY;
type V = number | null;
type MOP = { f: F, k: K, v: V };
type TXN = MOP[];

// TXN response
type TXN_RESPONSE = (['r', number, null | number[]] | ['append', number, number])[];

// only start http server after all subscriptions have been applied
let subscriptions_applied = false;

// Main entry point
async function main(): Promise<void> {
  console.info(`Connecting to SpacetimeDB...`);
  console.info(`\tURI: ${SPACETIMEDB_HOST}`);
  console.info(`\tModule: ${SPACETIMEDB_DB_NAME}`);
  console.info(`\twithConfirmedReads: ${CONFIRMED_READS}`);
  console.info(`\twithAutomaticReconnect: ${AUTOMATIC_RECONNECT}`);

  // Build and establish connection
  let db_builder = DbConnection
    .builder()
    .withUri(SPACETIMEDB_HOST)
    .withDatabaseName(SPACETIMEDB_DB_NAME)
    .withConfirmedReads(CONFIRMED_READS)
    .withToken(loadToken())
    .onConnect(onConnect)
    .onAutomaticReconnect(onAutomaticReconnect)
    .onDisconnect(onDisconnect)
    .onConnectError(onConnectError);
  db_builder = AUTOMATIC_RECONNECT ? db_builder.withAutomaticReconnect() : db_builder;
  const conn = db_builder.build();

  // wait for subscriptions to be applied before starting http server
  while (true) {
    if (subscriptions_applied) {
      break;
    }
    console.debug('waiting for subscriptions to be applied...');
    const timeout_promise = new Promise<void>((resolve, _) => setTimeout(() => resolve(), 100));
    await timeout_promise;
  }

  console.info('starting client endpoint...');
  const endpoint = client_endpoint(conn);
  const endpoint_available = new Promise<void>((resolve) => {
    endpoint
      .listen(CLIENT_PORT, () => {
        console.info('Jepsen endpoint listening at', endpoint.address());
        resolve();
      });

  });
  await endpoint_available;

  console.log();
  console.log('Press Ctrl+C to exit...');
}

// REST API for Jepsen transactions
// /table/f/technique
// e.g. /lists/txn/procedure 
function client_endpoint(conn: DbConnection): Server {
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
        console.log(`[endpoint]\tbody: ${body}`);

        let response: string;
        switch (method! + url) {
          case "GET" + "/ping":
            response = JSON.stringify({ type: 'ok' });
            break;

          case "POST" + "/lists/appends/reducer":
            const appends: TXN = JSON.parse(body) as TXN;

            // txn can timeout
            const appends_timer = setTimeout(() => {
              throw new Error('Transaction timed out!');
            }, CLIENT_TIMEOUT);

            await conn.reducers.appends({ txn: appends });
            clearTimeout(appends_timer);

            const appends_result: TXN_RESPONSE = [];
            for (const { k, v } of appends) {
              appends_result.push(['append', k, v!]);
            }

            response = JSON.stringify({ type: 'ok', value: appends_result });
            break;

          case "POST" + "/lists/reads/cache":
            const reads: TXN = JSON.parse(body) as TXN;

            // build a map with a key for each read mop
            const kv = new Map<number, number[] | null>();
            for (const { f, k, v } of reads) {
              if ((f != 'r') || (v != null)) {
                throw new Error(`invalid mop: ${{ f: f, k: k, v: v }}`);
              }

              kv.set(k, v);
            }

            // iterate through local client cache looking for desired keys
            // TODO: confirm that this is the only way to get atomic reads
            //       of multiple keys from the local client cache
            for (const { key, list } of conn.db.lists.iter()) {
              if (kv.has(key)) {
                kv.set(key, list);
              }
            }

            // convert local client cache kv lookup Map to a TXN_RESPONSE
            const reads_result: TXN_RESPONSE = [];
            for (const [k, v] of kv) {
              reads_result.push(['r', k, v]);
            }

            response = JSON.stringify({ type: 'ok', value: reads_result });
            break;

          case "POST" + "/lists/txn/procedure":
            const txn: TXN = JSON.parse(body) as TXN;

            // TODO: should a txn timeout?
            // if so, here? or let the Jepsen client timeout?
            // const txn_promise = conn.procedures.txn({ txn: txn });
            // const timeout_promise = new Promise((_, reject) => setTimeout(() => reject(new Error('Transaction in procedure timed out!')), CLIENT_TIMEOUT));
            // const txn_response = await Promise.race([txn_promise, timeout_promise]);
            const txn_response = await conn.procedures.txn({ txn: txn });

            const result = JSON.parse(txn_response) as TXN_RESPONSE;

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
        // DisconnectedError -> :fail
        // UnknownCallResultError -> :info
        console.error(`[endpoint] error: ${error.toString()}`);
        let response;
        if (error instanceof DisconnectedError) {
          response = JSON.stringify({ 'type': 'fail', 'error': error.toString() });
        } else if (error instanceof UnknownCallResultError) {
          response = JSON.stringify({ 'type': 'info', 'error': error.toString() });
        } else {
          response = JSON.stringify({ 'type': 'info', 'error': error.toString() });
        }

        console.error(`[endpoint] error: response: "${response}"`);

        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(response);
      }
    });
  });

  return endpoint;
}

function onConnect(
  conn: DbConnection,
  identity: Identity,
  token: string
): void {
  console.info();
  console.info('Connected to SpacetimeDB!');
  console.info(`\tIdentity: ${identity.toHexString().slice(0, 16)}...`);

  // Save token for future connections
  saveToken(token);

  // Subscribe to all tables
  conn
    .subscriptionBuilder()
    .onApplied(async ctx => {
      // log current lists in client cache
      const lists = [...ctx.db.lists.iter()];
      console.log();
      console.log(`Current lists (${lists.length}):`);
      if (lists.length === 0) {
        console.log('\t', '(none yet)');
      } else {
        for (const list of lists) {
          console.log('\t', { key: list.key, list: list.list });
        }
      }

      // signal that it's ok to start http server
      subscriptions_applied = true;
    })
    .onError((ctx: ErrorContext, error?: Error) => {
      console.error('onError: (subscriptionBuilder)');
      console.error('\tctx.event: ', ctx.event);
      console.error('\terror:', error);
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

function onAutomaticReconnect(
  _conn: DbConnection,
  identity: Identity,
  token: string
): void {
  console.info();
  console.info('Automatically Reconnected to SpacetimeDB!');
  console.info(`\tIdentity: ${identity.toHexString().slice(0, 16)}...`);

  // Save token for future connections
  saveToken(token);
}

function onDisconnect(ctx: ErrorContext,
  error?: Error,
  nextReconnectAttempt?: number,
  nextReconnectDelayMs?: number): void {
  if (error) {
    console.error();
    console.error('onDisconnect:');
    console.error('\tctx.event: ', ctx.event);
    console.error('\terror:', error);
    console.error();

    if (nextReconnectAttempt !== undefined) {
      console.error(`Retry ${nextReconnectAttempt} in ${nextReconnectDelayMs} ms`);
    } else {
      console.error('Exiting with exit code: ', 1);
      console.error();
      process.exit(1);
    }
  } else {
    console.log();
    console.log('onDisconnect:');
    console.log('\tctx.event: ', ctx.event);
    console.log();
    console.log('Exiting with exit code: ', 0);
    console.log();
    process.exit(0);
  }
}

function onConnectError(ctx: ErrorContext,
  error: Error,
  nextReconnectAttempt?: number,
  nextReconnectDelayMs?: number): void {
  console.error();
  console.error('onConnectError:');
  console.error('\tctx.event: ', ctx.event);
  console.error('\terror:', error);
  console.error();

  if (nextReconnectAttempt !== undefined) {
    console.error(`Retry ${nextReconnectAttempt} in ${nextReconnectDelayMs} ms`);
  } else {
    console.error('Exiting with exit code: ', 1);
    console.error();
    process.exit(1);
  }
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
