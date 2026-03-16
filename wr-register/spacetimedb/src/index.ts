import { schema, table, t } from 'spacetimedb/server';

const spacetimedb = schema({
  registers: table(
    { public: true },
    {
      k: t.i32().primaryKey(),
      v: t.i32(),
    }
  ),
});
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

// follow SpacetimeDB docs for accessing tables
// https://spacetimedb.com/docs/functions/reducers/#accessing-tables

export const insertRegister = spacetimedb.reducer(
  { k: t.i32(), v: t.i32() },
  (ctx, { k, v }) => {
    ctx.db.registers.insert({ k, v });
  }
);

export const deleteRegister = spacetimedb.reducer(
  { k: t.i32() },
  (ctx, { k }) => {
    ctx.db.registers.k.delete(k);
  }
);

export const updateRegister = spacetimedb.reducer(
  { k: t.i32(), v: t.i32() },
  (ctx, { k, v }) => {
    const register = ctx.db.registers.k.find(k);
    if (!register) {
      throw Error(`Unable to update ${{ k, v }}, primary key ${k} not in the table.`);
    }
    register.v = v;
    ctx.db.registers.k.update(register);
  }
);

export const upsertRegister = spacetimedb.reducer(
  { k: t.i32(), v: t.i32() },
  (ctx, { k, v }) => {
    const register = ctx.db.registers.k.find(k);
    if (register) {
      register.v = v;
      ctx.db.registers.k.update(register);
    } {
      ctx.db.registers.insert({ k: k, v: v });
    }
  }
);

export const listRegisters = spacetimedb.reducer(ctx => {
  console.info('listRegisters:');
  for (const register of ctx.db.registers.iter()) {
    console.info('\t', { k: register.k, v: register.v });
  }
});

// TODO: put in a shared type location for SpacetimeDB client
type F = 'r' | 'w';
type K = number;
type V = number | null;
type MOP = [F, K, V,];
type TXN = MOP[];

export const txn = spacetimedb.procedure(
  { value: t.string() },
  t.string(),
  (ctx, { value }) => {
    console.log(`[stdb] txn: value: "${value}"`);

    const txn: TXN = JSON.parse(value) as TXN;
    const res: TXN = [];

    console.log(`[stdb] txn: txn: ${txn}`);

    ctx.withTx(ctx => {
      for (const [f, k, v] of txn) {
        switch (f) {
          case 'r':
            const read = ctx.db.registers.k.find(k);
            if (read == null) {
              res.push(['r', k, null]);
            } else {
              res.push(['r', k, read.v]);
            }
            break;
          case 'w':
            upsertRegister(ctx, { k: k, v: v! });
            res.push([f, k, v])
            break;
        }
      }
    });

    const result = JSON.stringify(res);
    console.log(`[stdb] result: ${result}`);
    return result;
  });
