import bank;

i32 main() {
    bank.Account *a = bank.open("ann");
    a.deposit(5);
    a.deposit(10);
    bank.Account v;
    v.owner = "bob";
    v.balance = 1;
    v.deposit(2);
    fn(bank.Account *, i64) void dep = &bank.Account.deposit;
    dep(a, 100);
    bank.Account.deposit(&v, 1);
    printf("%s %s\n", a.report(), v.report());
    return 0;
}
