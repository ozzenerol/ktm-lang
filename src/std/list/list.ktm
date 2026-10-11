module list;
import mem;

pub struct List {
        void *value;
        List *next, *prev;
};

pub void listInit(List *l) {
        l.value = null;
        l.next = l.prev = l;
}

pub List *listNew() {
        List *ll = memAlloc(sizeof(List));
        ll.value = null;
        ll.next = ll.prev = ll;
        return ll;
}

pub void listPrepend(List *head, void *value) {
        List *node = listNew();
        node.value = value;
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
}

pub bool listEmpty(List *l) {
        return (l == null || l.next == l);
}

pub void listAppend(List *head, void *value) {
        List *tail = head.prev;
        List *node = listNew();
        node.value = value;
        node.prev = tail;
        node.next = head;
        tail.next = node;
        head.prev = node;
}

pub void *listDeque(List *l) {
        if (!l) { return null; }
        List *ll = (List *)l;
        if (ll.next == ll) {
                return null;
        }
        List *node = ll.next;
        void *val = node.value;
        node.next.prev = ll;
        ll.next = ll.next.next;
        memFree(node);
        return val;
}

pub void listInsertBefore(List *ll, List *newNode) {
        newNode.next = ll;
        newNode.prev = ll.prev;
        ll.prev.next = newNode;
        ll.prev = newNode;
}

pub void listInsertValueBefore(List *ll, void *value) {
        List *prev = listNew();
        prev.value = value;
        listInsertBefore(ll,prev);
}

pub void *listPop(List *l) {
        if (!l) { return null; }
        List *ll = (List *)l;
        if (ll.next == ll) {
                return null;
        }
        List *node = ll.prev;
        void *val = node.value;
        node.prev.next = ll;
        ll.prev = ll.prev.prev;
        memFree(node);
        return val;
}

pub void listRelease(List *ll, fn(void *arg) void FreeValue) {
        if (!ll) { return; }
        List *node = ll.next;
        List *next;
        while (node != ll) {
                next = node.next;
                if (FreeValue) {
                        FreeValue(node.value);
                }
                memFree(node);
                node = next;
        }
        memFree(ll);
}

pub void listClear(List *l, fn(void *value) void free_value=null)
{// Free every node (calling free_value on each value, if given) and keep
    // the head, empty: works on a ListInit head too. null is ignored.
        if (!l) { return; }
        List *node = l.next;
        List *next;
        while (node != l) {
                next = node.next;
                if (free_value) {
                        free_value(node.value);
                }
                memFree(node);
                node = next;
        }
        l.next = l.prev = l;
}

pub void *listRemove(List *node)
{// Unlink `node` from its list, free it and return its value. Never pass
    // the list's head; an empty head (linked to itself) is left alone and
    // null is returned, as for a null node.
        if (!node || node.next == node) { return null; }
        void *value = node.value;
        node.prev.next = node.next;
        node.next.prev = node.prev;
        memFree(node);
        return value;
}

pub void *listFirst(List *l)
{// The first value, without removing it; null if the list is empty or null
        if (!l || l.next == l) { return null; }
        return l.next.value;
}

pub void *listLast(List *l)
{// The last value, without removing it; null if the list is empty or null
        if (!l || l.prev == l) { return null; }
        return l.prev.value;
}

pub i64 listCount(List *ll) {
        if (!ll || ll == ll.next) { return 0; }
        auto node = ll.next;
        i64 count = 0;
        while (node != ll) {
                count++;
                node = node.next;
        }
        return count;
}

/* Move the nodes of l2 to the start of l1 and Free l2's head
    * Example
    * l1 = [1,2,3]
    * l2 = [4,5,6]
    * ListMergePrepend(l1,l2);
    * l1 = [4,5,6,1,2,3] (and linked the otherway too)
    * l2 has been freed and must not be used again. An empty l2 is just
    * freed; with l1 or l2 null nothing happens.
    */
pub void listMergePrepend(List *l1, List *l2) {
        if (!l1 || !l2) { return; }
        if (l2.next == l2) {
                memFree(l2);
                return;
        }
        List *h1 = l1.next;

        List *h2 = l2.next;
        List *t2 = l2.prev;

        l1.next = h2;
        h2.prev = l1;
        t2.next = h1;
        h1.prev = t2;
        memFree(l2);
}

/* Move the nodes of l2 to the end of l1 and Free l2's head.
    * Example
    * l1 = [1,2,3]
    * l2 = [4,5,6]
    * ListMergeAppend(l1,l2);
    * l1 = [1,2,3,4,5,6] (and linked the otherway too)
    * l2 has been freed and must not be used again. An empty l2 is just
    * freed; with l1 or l2 null nothing happens.
    * */
pub void listMergeAppend(List *l1, List *l2) {
        if (!l1 || !l2) { return; }
        if (l2.next == l2) {
                memFree(l2);
                return;
        }
        List *t1 = l1.prev;

        List *h2 = l2.next;
        List *t2 = l2.prev;

        h2.prev = t1;
        t1.next = h2;
        t2.next = l1;
        l1.prev = t2;
        memFree(l2);
}
