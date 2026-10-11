module list;
import mem;

pub struct List {
        void *value;
        List *next, *prev;
};

pub void List.init() {
        self.value = null;
        self.next = self.prev = self;
}

pub List *new() {
        List *ll = mem.alloc(sizeof(List));
        ll.value = null;
        ll.next = ll.prev = ll;
        return ll;
}

pub void List.prepend(void *value) {
        List *node = new();
        node.value = value;
        node.prev = self;
        node.next = self.next;
        self.next.prev = node;
        self.next = node;
}

pub bool List.isEmpty() {
        return (self == null || self.next == self);
}

pub void List.append(void *value) {
        List *tail = self.prev;
        List *node = new();
        node.value = value;
        node.prev = tail;
        node.next = self;
        tail.next = node;
        self.prev = node;
}

pub void *List.shift() {
        if (!self) { return null; }
        List *ll = (List *)self;
        if (ll.next == ll) {
                return null;
        }
        List *node = ll.next;
        void *val = node.value;
        node.next.prev = ll;
        ll.next = ll.next.next;
        mem.free(node);
        return val;
}

pub void List.insertNodeBefore(List *newNode) {
        newNode.next = self;
        newNode.prev = self.prev;
        self.prev.next = newNode;
        self.prev = newNode;
}

pub void List.insertBefore(void *value) {
        List *prev = new();
        prev.value = value;
        self.insertNodeBefore(prev);
}

pub void *List.pop() {
        if (!self) { return null; }
        List *ll = (List *)self;
        if (ll.next == ll) {
                return null;
        }
        List *node = ll.prev;
        void *val = node.value;
        node.prev.next = ll;
        ll.prev = ll.prev.prev;
        mem.free(node);
        return val;
}

pub void List.release(fn(void *arg) void FreeValue) {
        if (!self) { return; }
        List *node = self.next;
        List *next;
        while (node != self) {
                next = node.next;
                if (FreeValue) {
                        FreeValue(node.value);
                }
                mem.free(node);
                node = next;
        }
        mem.free(self);
}

pub void List.clear(fn(void *value) void freeValue=null)
{// Free every node (calling free_value on each value, if given) and keep
    // the head, empty: works on a ListInit head too. null is ignored.
        if (!self) { return; }
        List *node = self.next;
        List *next;
        while (node != self) {
                next = node.next;
                if (freeValue) {
                        freeValue(node.value);
                }
                mem.free(node);
                node = next;
        }
        self.next = self.prev = self;
}

pub void *List.remove()
{// Unlink `node` from its list, free it and return its value. Never pass
    // the list's head; an empty head (linked to itself) is left alone and
    // null is returned, as for a null node.
        if (!self || self.next == self) { return null; }
        void *value = self.value;
        self.prev.next = self.next;
        self.next.prev = self.prev;
        mem.free(self);
        return value;
}

pub void *List.first()
{// The first value, without removing it; null if the list is empty or null
        if (!self || self.next == self) { return null; }
        return self.next.value;
}

pub void *List.last()
{// The last value, without removing it; null if the list is empty or null
        if (!self || self.prev == self) { return null; }
        return self.prev.value;
}

pub i64 List.count() {
        if (!self || self == self.next) { return 0; }
        auto node = self.next;
        i64 count = 0;
        while (node != self) {
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
pub void List.mergePrepend(List *l2) {
        if (!self || !l2) { return; }
        if (l2.next == l2) {
                mem.free(l2);
                return;
        }
        List *h1 = self.next;

        List *h2 = l2.next;
        List *t2 = l2.prev;

        self.next = h2;
        h2.prev = self;
        t2.next = h1;
        h1.prev = t2;
        mem.free(l2);
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
pub void List.mergeAppend(List *l2) {
        if (!self || !l2) { return; }
        if (l2.next == l2) {
                mem.free(l2);
                return;
        }
        List *t1 = self.prev;

        List *h2 = l2.next;
        List *t2 = l2.prev;

        h2.prev = t1;
        t1.next = h2;
        t2.next = self;
        self.prev = t2;
        mem.free(l2);
}
