import { schema, SenderError, t, table, } from 'spacetimedb/server';

// TODO: put in a shared type location for SpacetimeDB client

// append only keyed list
const KEY = t.i32();
const ELEMENT = t.i32();
const LIST = t.array(t.i32());
const LISTS = t.array(LIST);

// txn [{ f: append|r k: key v: element|list }...]
// MOP
const F = t.string();
const K = KEY;
// request MOP
const REQ_V = t.option(ELEMENT);
const REQ_MOP = t.object('REQ_MOP', { f: F, k: K, v: REQ_V });
const REQ_TXN = t.array(REQ_MOP);
// response MOP
const RES_V_APPEND = t.option(ELEMENT);
const RES_V_READ = t.option(LIST);
const RES_MOP = t.object('RES_MOP', { f: F, k: K, v_append: RES_V_APPEND, v_read: RES_V_READ });
const RES_TXN = t.array(RES_MOP);

const lists = table(
  {
    name: 'lists',
    public: true
  },
  {
    key: KEY.primaryKey(),
    list: LIST,
  }
);

const spacetimedb = schema({ lists });
export default spacetimedb;

export const init = spacetimedb.init(_ctx => {
  // Called when the module is initially published
});

export const onConnect = spacetimedb.clientConnected(_ctx => {
  // Called every time a new client connects
});

export const onDisconnect = spacetimedb.clientDisconnected(_ctx => {
  // Called every time a client disconnects
});

// execute a transaction for a keyed append only list
export const txn = spacetimedb.procedure(
  { txn: REQ_TXN },
  RES_TXN,
  (ctx, { txn }) => {
    console.log(`[txn] txn: "${txn}"`);

    // TODO: remove debugging
    for (const mop of txn) {
      console.log(`[txn] txn: mop: f: ${mop.f}, k: ${mop.k}, v: ${mop.v}`);
    }

    const res: { f: string, k: number, v_append: number | undefined, v_read: number[] | undefined }[] = [];

    ctx.withTx(ctx => {
      for (const { f, k, v } of txn) {
        switch (f) {
          case 'r':
            const read = ctx.db.lists.key.find(k);
            if (read == null) {
              res.push({ f: 'r', k: k, v_read: undefined, v_append: undefined });
            } else {
              res.push({ f: 'r', k: k, v_read: read.list, v_append: undefined });
            }
            break;
          case 'append':
            let list = ctx.db.lists.key.find(k);
            if (list == null) {
              list = { key: k, list: [v!] };
              ctx.db.lists.insert(list);
            } else {
              list.list.concat(v!);
              ctx.db.lists.key.update(list);
            }
            res.push({ f: 'append', k: k, v_read: undefined, v_append: v! });
            break;
        }
      }
    });

    // TODO: remove debugging
    for (const mop of res) {
      console.log(`[txn] res: mop: f: ${mop.f}, k: ${mop.k}, v_read: ${mop.v_read}, v_append: ${mop.v_append}`);
    }

    console.log(`[txn] res: ${res}`);
    return res;
  });
