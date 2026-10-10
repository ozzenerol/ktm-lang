import shop;

i32 main() {
  shop.Box b;
  b.weight = 5;
  shop.Order o;
  o.box = &b;
  b.back = &o;
  o.first.price = 7;
  o.kind = shop.Kind.big;
  o.sizes[shop.SLOTS - 1] = 100;
  o.first.unit.cents = 3;
  "%d %d %d %d\n", shop.orderTotal(&o), shop.orderBox(&o)->weight,
      shop.boxWeight(&b), sizeof(shop.Order) / 8;
  "%d %d %d\n", o.first.total(), sizeof(shop.g_slots) / 8,
      shop.kindOf(&o) == shop.Kind.big;
  return 0;
}
