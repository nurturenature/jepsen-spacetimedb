import { schema, SenderError, t, table, } from 'spacetimedb/server';

// TODO: put in a shared type location for SpacetimeDB client

// append only keyed list
const KEY = t.i32();
const ELEMENT = t.i32();
const LIST = t.array(t.i32());

// txn [{ f: 'append'|'r' k: key v: element|list|null }...]
// MOP
const F = t.string();
const K = KEY;
// request MOP
const REQ_V = t.option(ELEMENT);
const REQ_MOP = t.object('REQ_MOP', { f: F, k: K, v: REQ_V });
const REQ_TXN = t.array(REQ_MOP);
// TXN response
type TXN_RESPONSE = (['r', number, null | number[]] | ['append', number, number])[];

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
  t.string(),
  (ctx, { txn }) => {
    console.log(`[txn] txn: "${txn.toString()}"`);

    const res: TXN_RESPONSE = [];

    ctx.withTx(ctx => {
      for (const { f, k, v } of txn) {
        switch (f) {
          case 'r':
            const read_list = ctx.db.lists.key.find(k);
            if (read_list == null) {
              res.push(['r', k, null]);
            } else {
              res.push(['r', k, read_list.list]);
            }
            break;
          case 'append':
            const append_list = ctx.db.lists.key.find(k);
            if (append_list == null) {
              const new_list = { key: k, list: [v!] };
              ctx.db.lists.insert(new_list);
            } else {
              append_list.list.push(v!);
              ctx.db.lists.key.update(append_list);
            }
            res.push(['append', k, v!]);
            break;
        }
      }
    });

    // TODO: remove debugging
    console.log(`[txn] res: ${res}`);

    const result = JSON.stringify(res);

    console.log(`[txn] result: ${result}`);
    return result;
  });
