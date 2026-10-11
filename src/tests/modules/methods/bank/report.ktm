module bank;
import str;

/* uses a method of the other file */
pub u8 *Account.report() {
    self.deposit(0);
    return str.format("%s:%d:%d", self.owner, self.balance, gOps);
}
