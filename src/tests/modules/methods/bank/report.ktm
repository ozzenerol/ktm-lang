module bank;
import cstr;

/* uses a method of the other file */
pub u8 *Account.report() {
    self.deposit(0);
    return cstr.format("%s:%d:%d", self.owner, self.balance, gOps);
}
