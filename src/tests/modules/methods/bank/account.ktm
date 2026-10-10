module bank;

pub struct Account {
  u8 *owner;
  i64 balance;
};

i64 g_ops = 0;

pub Account *open(u8 *owner)
{
  Account *a = MAlloc(sizeof(Account));
  a.owner = owner;
  a.balance = 0;
  return a;
}

pub void Account.deposit(i64 x)
{
  self.balance += x;
  self.audit();
}

/* private: only the module calls it */
void Account.audit()
{
  g_ops++;
}
