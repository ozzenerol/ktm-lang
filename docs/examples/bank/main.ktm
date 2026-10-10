// Bank: accounts, monthly interest and a transaction log.
//
//   ktm main.ktm -o bank && ./bank
//   ktm -jit main.ktm
//
// Shows: classes and inheritance, function pointers as "methods",
// exceptions for errors, linked lists, and integer money (cents).

#define ERR_FUNDS  'Funds'
#define ERR_FROZEN 'Frozen'

class Account
{
  U8 *owner;
  I64 balance;          // in cents
  Bool frozen;
  // Per-kind behaviour: interest earned in a month.
  I64 (*interest)(Account *a);
};

class Savings : Account
{
  I64 rate_bp;          // annual rate in basis points (1/100 of a percent)
};

class Txn
{
  U8 *owner;
  U8 *what;
  I64 amount;
};

List *ledger;

I64 NoInterest(Account *a)
{
  return 0;
}

I64 SavingsInterest(Account *a)
{
  Savings *s = a;
  return s->balance * s->rate_bp / 10000 / 12;
}

U0 InitAccount(Account *a, U8 *owner, I64 cents)
{
  a->owner = owner;
  a->balance = cents;
  a->frozen = FALSE;
  a->interest = &NoInterest;
}

U0 Record(Account *a, U8 *what, I64 amount)
{
  Txn *t = MAlloc(sizeof(Txn));
  t->owner = a->owner;
  t->what = what;
  t->amount = amount;
  ListAppend(ledger, t);
}

U0 Withdraw(Account *a, I64 cents)
{
  if (a->frozen) throw(ERR_FROZEN);
  if (cents > a->balance) throw(ERR_FUNDS);
  a->balance -= cents;
  Record(a, "withdraw", -cents);
}

U0 Deposit(Account *a, I64 cents)
{
  if (a->frozen) throw(ERR_FROZEN);
  a->balance += cents;
  Record(a, "deposit", cents);
}

// Moves money between accounts, or neither changes.
Bool Transfer(Account *from, Account *to, I64 cents)
{
  Bool withdrawn = FALSE;
  try {
    Withdraw(from, cents);
    withdrawn = TRUE;
    Deposit(to, cents);
  } catch {
    if (withdrawn) {                 // the deposit failed: put it back
      from->balance += cents;
      Record(from, "refund", cents);
    }
    U8 *why = "account frozen";
    if (Fs->except_ch == ERR_FUNDS) why = "insufficient funds";
    "  transfer %s -> %s refused: %s\n", from->owner, to->owner, why;
    return FALSE;
  }
  return TRUE;
}

U0 PrintMoney(I64 cents)
{
  if (cents < 0) {
    "-";
    cents = -cents;
  }
  "%d.%02d", cents / 100, cents % 100;
}

U0 Statement(Account *a)
{
  "  %-6s ", a->owner;
  PrintMoney(a->balance);
  if (a->frozen) " (frozen)";
  "\n";
}

U0 Main()
{
  ledger = ListNew();

  Account alice;
  InitAccount(&alice, "alice", 120000);

  Savings bob;
  InitAccount(&bob, "bob", 50000);
  bob.rate_bp = 450;                 // 4.5% a year
  bob.interest = &SavingsInterest;

  Account carol;
  InitAccount(&carol, "carol", 2500);

  Account *all[3];
  all[0] = &alice;
  all[1] = &bob;
  all[2] = &carol;

  "Opening balances\n";
  for (I64 i = 0; i < 3; i++) Statement(all[i]);

  "Transfers\n";
  Transfer(&alice, &bob, 30000);
  Transfer(&carol, &alice, 9999);    // carol only has 25.00
  carol.frozen = TRUE;
  Transfer(&bob, &carol, 100);

  "Month end\n";
  for (I64 i = 0; i < 3; i++) {
    Account *a = all[i];
    I64 earned = a->interest(a);
    if (earned > 0) {
      a->balance += earned;
      Record(a, "interest", earned);
    }
    Statement(a);
  }

  "Ledger (%d entries)\n", ListCount(ledger);
  for (List *it = ledger->next; it != ledger; it = it->next) {
    Txn *t = it->value;
    "  %-6s %-9s ", t->owner, t->what;
    PrintMoney(t->amount);
    "\n";
  }
}
