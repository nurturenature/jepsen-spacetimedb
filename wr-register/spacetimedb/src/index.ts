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

// follow SpacetimeDB docs for accessing tables
// https://spacetimedb.com/docs/functions/reducers/#accessing-tables

export const insertRegister = spacetimedb.reducer(
  { k: t.i64(), v: t.i64() },
  (ctx, { k, v }) => {
    ctx.db.registers.insert({ k, v });
  }
);

export const deleteRegister = spacetimedb.reducer(
  { k: t.i64() },
  (ctx, { k }) => {
    ctx.db.registers.k.delete(k);
  }
);

export const updateRegister = spacetimedb.reducer(
  { k: t.i64(), v: t.i64() },
  (ctx, { k, v }) => {
    const register = ctx.db.registers.k.find(k);
    if (!register) {
      throw Error('Unable to update {$k $v}, primary key $k not in the table.');
    }
    register.v = v;
    ctx.db.registers.k.update(register);
  }
);

export const upsertRegister = spacetimedb.reducer(
  { k: t.i64(), v: t.i64() },
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
