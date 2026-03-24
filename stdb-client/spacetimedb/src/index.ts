import { schema, SenderError, t, table, } from 'spacetimedb/server';

// TODO: put in a shared type location for SpacetimeDB client

// append only keyed list
const KEY = t.i32();
const LIST = t.option(t.string());
const LISTS = t.array(LIST);

// txn
const F = t.string();
const K = KEY;
const V = t.option(LIST);
const MOP = t.object('MOP', { f: F, k: K, v: V });
const TXN = t.array(MOP);

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
export const list_append = spacetimedb.procedure(
  { txn: TXN },
  TXN,
  (ctx, { txn }) => {
    console.log(`[list_append] txn: "${txn}"`);

    const res: typeof txn = [];

    ctx.withTx(ctx => {
      for (const { f, k, v } of txn) {
        switch (f) {
          case 'r':
            const read = ctx.db.lists.key.find(k);
            if (read == null) {
              res.push({ f: 'r', k: k, v: undefined });
            } else {
              res.push({ f: 'r', k: k, v: read.list });
            }
            break;
          case 'append':
            let list = ctx.db.lists.key.find(k);
            if (list == null) {
              list = { key: k, list: v };
              ctx.db.lists.insert(list);
            } else {
              list.list += ' ' + v;
              ctx.db.lists.key.update(list);
            }
            res.push({ f: 'append', k: k, v: list.list });
            break;
        }
      }
    });

    console.log(`[list_append] res: ${res}`);
    return res;
  });
