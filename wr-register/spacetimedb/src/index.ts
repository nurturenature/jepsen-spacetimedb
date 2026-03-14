import { schema, table, t } from 'spacetimedb/server';

const spacetimedb = schema({
  registers: table(
    { public: true },
    {
      k: t.i64().primaryKey(),
      v: t.i64(),
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

export const insertRegister = spacetimedb.reducer(
  { k: t.i64(), v: t.i64() },
  (ctx, { k, v }) => {
    ctx.db.registers.insert({ k, v });
  }
);

export const deleteRegister = spacetimedb.reducer(
  { k: t.i64(), v: t.i64() },
  (ctx, { k, v }) => {
    ctx.db.registers.delete({ k, v });
  }
);

export const listRegisters = spacetimedb.reducer(ctx => {
  console.info('listRegisters:');
  for (const register of ctx.db.registers.iter()) {
    console.info('\t', { k: register.k, v: register.v });
  }
});
