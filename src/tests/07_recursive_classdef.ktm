import test;

import mem;

struct TestListNode;
struct TestListNode {
    i64 value;
    TestListNode *next, *tail;
};

struct TestList {
    i64 size;
    TestListNode *root;
};

TestList *testListNew() {
    TestList *ll = mem.alloc(sizeof(TestList));
    ll.size = 0;
    ll.root = null;
    return ll;
}

void testListAdd(TestList *ll, i64 value) {
    TestListNode *ln = mem.alloc(sizeof(TestListNode));
    ln.value = value;
    ln.next = ll.root;
    ll.root = ln;
    ll.size++;
}

i32 main() {
    printf("Test - Linked list recusive definition class: ");
    i64 correct = 0, testCases = 5;
    TestList *ll = testListNew();
    TestListNode *ln;
    testListAdd(ll,1);
    testListAdd(ll,2);
    testListAdd(ll,3);
    testListAdd(ll,4);
    ln = ll.root;

    if (ln.value == 4) {
        correct++;
        ln = ln.next;
    }

    if (ln.value == 3) {
        correct++;
        ln = ln.next;
    }

    if (ln.value == 2) {
        correct++;
        ln = ln.next;
    }

    if (ln.value == 1) {
        correct++;
        ln = ln.next;
    }

    if (!ln) {
        correct++;
    }

    test.printResult(correct,testCases);
    printf("====\n");
    return 0;
}
