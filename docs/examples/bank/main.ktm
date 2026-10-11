// Bank: accounts, monthly interest and a transaction log.
//
//   ktm main.ktm -o bank && ./bank
//   ktm -jit main.ktm
//
// Shows: classes and inheritance, function pointers as "methods",
// exceptions for errors, linked lists, and integer money (cents).

import list, mem;

const i64 ERR_FUNDS = 'Funds';
const i64 ERR_FROZEN = 'Frozen';

struct Account {
    u8 *owner;
    i64 balance;          // in cents
    bool frozen;
    // Per-kind behaviour: interest earned in a month.
    fn(Account *a) i64 interest;
};

struct Savings : Account {
    i64 rateBp;          // annual rate in basis points (1/100 of a percent)
};

struct Txn {
    u8 *owner;
    u8 *what;
    i64 amount;
};

list.List *ledger;

i64 noInterest(Account *a) {
    return 0;
}

i64 savingsInterest(Account *a) {
    Savings *s = a;
    return s.balance * s.rateBp / 10000 / 12;
}

void initAccount(Account *a, u8 *owner, i64 cents) {
    a.owner = owner;
    a.balance = cents;
    a.frozen = false;
    a.interest = &noInterest;
}

void record(Account *a, u8 *what, i64 amount) {
    Txn *t = mem.alloc(sizeof(Txn));
    t.owner = a.owner;
    t.what = what;
    t.amount = amount;
    ledger.append(t);
}

void withdraw(Account *a, i64 cents) {
    if (a.frozen) { throw(ERR_FROZEN); }
    if (cents > a.balance) { throw(ERR_FUNDS); }
    a.balance -= cents;
    record(a, "withdraw", -cents);
}

void deposit(Account *a, i64 cents) {
    if (a.frozen) { throw(ERR_FROZEN); }
    a.balance += cents;
    record(a, "deposit", cents);
}

// Moves money between accounts, or neither changes.
bool transfer(Account *from, Account *to, i64 cents) {
    bool withdrawn = false;
    try {
        withdraw(from, cents);
        withdrawn = true;
        deposit(to, cents);
    } catch (Error e) {
        if (withdrawn) {                 // the deposit failed: put it back
            from.balance += cents;
            record(from, "refund", cents);
        }
        u8 *why = "account frozen";
        if (e.code == ERR_FUNDS) { why = "insufficient funds"; }
        printf("  transfer %s -> %s refused: %s\n", from.owner, to.owner, why);
        return false;
    }
    return true;
}

void printMoney(i64 cents) {
    if (cents < 0) {
        printf("-");
        cents = -cents;
    }
    printf("%d.%02d", cents / 100, cents % 100);
}

void statement(Account *a) {
    printf("  %-6s ", a.owner);
    printMoney(a.balance);
    if (a.frozen) { printf(" (frozen)"); }
    printf("\n");
}

void main() {
    ledger = list.new();

    Account alice;
    initAccount(&alice, "alice", 120000);

    Savings bob;
    initAccount(&bob, "bob", 50000);
    bob.rateBp = 450;                 // 4.5% a year
    bob.interest = &savingsInterest;

    Account carol;
    initAccount(&carol, "carol", 2500);

    Account *all[3];
    all[0] = &alice;
    all[1] = &bob;
    all[2] = &carol;

    printf("Opening balances\n");
    for (i64 i = 0; i < 3; i++) { statement(all[i]); }

    printf("Transfers\n");
    transfer(&alice, &bob, 30000);
    transfer(&carol, &alice, 9999);    // carol only has 25.00
    carol.frozen = true;
    transfer(&bob, &carol, 100);

    printf("Month end\n");
    for (i64 i = 0; i < 3; i++) {
        Account *a = all[i];
        i64 earned = a.interest(a);
        if (earned > 0) {
            a.balance += earned;
            record(a, "interest", earned);
        }
        statement(a);
    }

    printf("Ledger (%d entries)\n", ledger.count());
    for (list.List *it = ledger.next; it != ledger; it = it.next) {
        Txn *t = it.value;
        printf("  %-6s %-9s ", t.owner, t.what);
        printMoney(t.amount);
        printf("\n");
    }
}
