module bank;

/* uses a method of the other file */
pub u8 *Account.report()
{
  self.deposit(0);
  return MStrPrint("%s:%d:%d", self.owner, self.balance, g_ops);
}
