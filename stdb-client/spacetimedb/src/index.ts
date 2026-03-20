import { schema, SenderError, t, table, } from 'spacetimedb/server';

// TODO: put in a shared type location for SpacetimeDB client

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

const registers = table(
  {
    name: 'registers',
    public: true
  },
  {
    k: t.i32().primaryKey(),
    v: t.i32(),
  }
);

const ledger = table(
  {
    name: 'ledger',
    public: true
  },
  {
    account: t.i32().primaryKey(),
    balance: t.i32(),
  }
);

const spacetimedb = schema({ registers, ledger });
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

// following SpacetimeDB docs for accessing tables
// https://spacetimedb.com/docs/functions/reducers/#accessing-tables

// wr-register

export const insertRegister = spacetimedb.reducer(
  { k: t.i32(), v: t.i32() },
  (ctx, { k, v }) => {
    try {
      ctx.db.registers.insert({ k, v });
    } catch (error) {
      throw new SenderError(`Error inserting ${{ k, v }}: ${error}`);
    }
  }
);

export const deleteRegister = spacetimedb.reducer(
  { k: t.i32() },
  (ctx, { k }) => {
    try {
      ctx.db.registers.k.delete(k);
    } catch (error) {
      throw new SenderError(`Error deleting ${k}: ${error}`);
    }
  }
);

export const updateRegister = spacetimedb.reducer(
  { k: t.i32(), v: t.i32() },
  (ctx, { k, v }) => {
    const register = ctx.db.registers.k.find(k);
    if (!register) {
      throw new SenderError(`Unable to update ${{ k, v }}, primary key ${k} not in the table.`);
    }
    register.v = v;
    try {
      ctx.db.registers.k.update(register);
    } catch (error) {
      throw new SenderError(`Error updating ${{ k, v }}: ${error}`);
    }
  }
);

export const upsertRegister = spacetimedb.reducer(
  { k: t.i32(), v: t.i32() },
  (ctx, { k, v }) => {
    try {
      const register = ctx.db.registers.k.find(k);
      if (register) {
        register.v = v;
        ctx.db.registers.k.update(register);
      } {
        ctx.db.registers.insert({ k: k, v: v });
      }
    } catch (error) {
      throw new SenderError(`Error upserting ${{ k, v }}: ${error}`);
    }
  }
);

export const listRegisters = spacetimedb.reducer(ctx => {
  console.info('listRegisters:');
  for (const register of [...ctx.db.registers.iter()]) {
    console.info('\t', { k: register.k, v: register.v });
  }
});

// no try/catch, rely on:
// - called reducer to throw SenderError
// - or it's truly unexpected and should surface as an uncaught Error
export const registersTxn = spacetimedb.procedure(
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

// ledger

export const insertLedger = spacetimedb.reducer(
  { account: t.i32(), balance: t.i32() },
  (ctx, { account, balance }) => {
    try {
      ctx.db.ledger.insert({ account, balance });
    } catch (error) {
      throw new SenderError(`Error inserting ${{ account, balance }}: ${error}`);
    }
  }
);

export const deleteLedger = spacetimedb.reducer(
  { account: t.i32() },
  (ctx, { account }) => {
    try {
      ctx.db.ledger.account.delete(account);
    } catch (error) {
      throw new SenderError(`Error deleting ${account}: ${error}`);
    }
  }
);

export const updateLedger = spacetimedb.reducer(
  { account: t.i32(), balance: t.i32() },
  (ctx, { account, balance }) => {
    const entry = ctx.db.ledger.account.find(account);
    if (!entry) {
      throw new SenderError(`Unable to update ${{ account, balance }}, primary key ${account} not in the table.`);
    }
    entry.balance = balance;
    try {
      ctx.db.ledger.account.update(entry);
    } catch (error) {
      throw new SenderError(`Error updating ${{ account, balance }}: ${error}`);
    }
  }
);

export const upsertLedger = spacetimedb.reducer(
  { account: t.i32(), balance: t.i32() },
  (ctx, { account, balance }) => {
    try {
      const entry = ctx.db.ledger.account.find(account);
      if (entry) {
        entry.balance = balance;
        ctx.db.ledger.account.update(entry);
      } {
        ctx.db.ledger.insert({ account: account, balance: balance });
      }
    } catch (error) {
      throw new SenderError(`Error upserting ${{ account, balance }}: ${error}`);
    }
  }
);

export const listLedger = spacetimedb.reducer(ctx => {
  console.info('listLedger:');
  for (const entry of [...ctx.db.ledger.iter()]) {
    console.info('\t', { account: entry.account, balance: entry.balance });
  }
});

export const setupLedger = spacetimedb.reducer(
  { accounts: t.array(t.i32()), balance: t.i32() },
  (ctx, { accounts, balance }) => {
    console.log(`[stdb] ledgerSetup: { accounts: ${accounts}, balance: ${balance} }`);

    // TODO: is there a better way?
    for (const entry of [...ctx.db.ledger.iter()]) {
      deleteLedger(ctx, { account: entry.account });
    }

    for (const account of accounts) {
      upsertLedger(ctx, { account: account, balance: balance });
    }

    return;
  });

export const ledgerRead = spacetimedb.procedure(
  {},
  t.string(),
  (ctx, { }) => {
    console.log('[stdb][ledgerRead] invoke');

    const ledger: LEDGER = [];
    ctx.withTx(ctx => {
      for (const entry of [...ctx.db.ledger.iter()]) {
        ledger.push(entry);
      }
    });

    const result = JSON.stringify(ledger);
    console.log(`[stdb][ledgerRead] return: ${result}`);
    return result;
  });

// no try/catch, rely on:
// - called reducer to throw SenderError
// - or it's truly unexpected and should surface as an uncaught Error
export const ledgerTransfer = spacetimedb.procedure(
  { from: t.i32(), to: t.i32(), amount: t.i32() },
  t.unit(),
  (ctx, { from, to, amount }) => {
    console.log(`[stdb] ledgerTransfer: "${{ from: from, to: to, amount: amount }}"`);

    ctx.withTx(ctx => {
      const from_row = ctx.db.ledger.account.find(from);
      const to_row = ctx.db.ledger.account.find(to);
      if (!from_row || !to_row) {
        throw new SenderError(`Could not find both from and to accounts for transfer: ${{ from: from, to: to, amount: amount }}`);
      }

      from_row.balance -= amount;
      to_row.balance += amount;

      updateLedger(ctx, from_row);
      updateLedger(ctx, to_row);
    });

    return {};
  });
