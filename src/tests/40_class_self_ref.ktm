import test;

import mem;

/* Self-referencing class without a forward declaration. 07 covers the
 * forward-decl form `class Node; class Node { Node *next; ... };`; this covers
 * the bare form, which used to raise "Type Node has not been declared". */

struct Node {
    Node *next;
    i64 v;
};

bool twoNodeList() {
    Node *a = mem.alloc(sizeof(Node));
    Node *b = mem.alloc(sizeof(Node));
    a.v = 1; a.next = b;
    b.v = 2; b.next = null;
    bool ok = a.v == 1 &&
                        a.next == b &&
                        a.next.v == 2 &&
                        a.next.next == null;
    mem.free(b);
    mem.free(a);
    return ok;
}

struct Tree {
    Tree *left, *right;
    i64 v;
};

bool siblingChildren() {
    Tree *root = mem.alloc(sizeof(Tree));
    Tree *l = mem.alloc(sizeof(Tree));
    Tree *r = mem.alloc(sizeof(Tree));
    root.left = l;  l.v = 10; l.left = l.right = null;
    root.right = r; r.v = 20; r.left = r.right = null;
    root.v = 1;
    bool ok = root.left.v == 10 && root.right.v == 20;
    mem.free(r); mem.free(l); mem.free(root);
    return ok;
}

i32 main() {
    printf("Test - Class self-reference (no forward decl): ");
    i64 correct = 0, tests = 2;
    if (twoNodeList()) { correct++; }
    if (siblingChildren()) { correct++; }
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
