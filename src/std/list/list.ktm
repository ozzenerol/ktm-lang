module list;
import mem;

/* A doubly linked list of T, a value type: `scoped list.List<Job *> q =
 * list.new<Job *>();`. append/prepend return the node, so remove(node)
 * takes it out later in O(1) (a timer, a client to drop). Walk it with
 * `for (auto n = l.head; n; n = n.next)` (n.value); head and tail are
 * null for an empty list. A List of String owns its Strings: free() and
 * clear() free them; remove, shift and pop hand the value back to you.
 * Other values are yours: free() frees the nodes, not what they point
 * to. */
/* Freeing a value the List owns: a String; anything else is the
 * caller's */
void listValueFree(String s) { mem.free(s.ptr); }
void listValueFree<T>(T x) {}

pub struct Node<T> {
        T value;
        Node<T> *next, *prev;
};

pub struct List<T> {
        Node<T> *head, *tail;
        i64 len;
};

/* An empty list (nothing allocated until the first node) */
pub List<T> new<T>() {
        List<T> l;
        l.head = null;
        l.tail = null;
        l.len = 0;
        return l;
}

static Node<T> *listNode<T>(T value) {
        Node<T> *n = mem.alloc(sizeof(Node<T>));
        n.value = value;
        n.next = null;
        n.prev = null;
        return n;
}

/* value at the end; its node */
pub Node<T> *List<T>.append(T value) {
        Node<T> *n = listNode<T>(value);
        n.prev = self.tail;
        if (self.tail) { self.tail.next = n; } else { self.head = n; }
        self.tail = n;
        self.len++;
        return n;
}

/* value at the front; its node */
pub Node<T> *List<T>.prepend(T value) {
        Node<T> *n = listNode<T>(value);
        n.next = self.head;
        if (self.head) { self.head.prev = n; } else { self.tail = n; }
        self.head = n;
        self.len++;
        return n;
}

/* value before node `at` of this list (at the end for a null `at`); its node */
pub Node<T> *List<T>.insertBefore(Node<T> *at, T value) {
        if (!at) { return self.append(value); }
        Node<T> *n = listNode<T>(value);
        n.next = at;
        n.prev = at.prev;
        if (at.prev) { at.prev.next = n; } else { self.head = n; }
        at.prev = n;
        self.len++;
        return n;
}

/* value after node `at` of this list (at the front for a null `at`); its node */
pub Node<T> *List<T>.insertAfter(Node<T> *at, T value) {
        if (!at) { return self.prepend(value); }
        Node<T> *n = listNode<T>(value);
        n.prev = at;
        n.next = at.next;
        if (at.next) { at.next.prev = n; } else { self.tail = n; }
        at.next = n;
        self.len++;
        return n;
}

/* Take node n (of this list) out, free it and return its value */
pub T List<T>.remove(Node<T> *n) {
        T value = n.value;
        if (n.prev) { n.prev.next = n.next; } else { self.head = n.next; }
        if (n.next) { n.next.prev = n.prev; } else { self.tail = n.prev; }
        self.len--;
        mem.free(n);
        return value;
}

static T listZero<T>(bool *ok) {
        T zero;
        mem.set(&zero, 0, sizeof(T));
        if (ok) { *ok = false; }
        return zero;
}

/* The first value, taken out; for an empty list a zeroed T, and *ok
 * (when given) false */
pub T List<T>.shift(bool *ok = null) {
        if (!self.head) { return listZero<T>(ok); }
        if (ok) { *ok = true; }
        return self.remove(self.head);
}

/* The last value, taken out; as shift for an empty list */
pub T List<T>.pop(bool *ok = null) {
        if (!self.tail) { return listZero<T>(ok); }
        if (ok) { *ok = true; }
        return self.remove(self.tail);
}

/* The first value, left in; as shift for an empty list */
pub T List<T>.first(bool *ok = null) {
        if (!self.head) { return listZero<T>(ok); }
        if (ok) { *ok = true; }
        return self.head.value;
}

/* The last value, left in; as shift for an empty list */
pub T List<T>.last(bool *ok = null) {
        if (!self.tail) { return listZero<T>(ok); }
        if (ok) { *ok = true; }
        return self.tail.value;
}

/* Every node freed; the list is empty and can be used again */
pub void List<T>.clear() {
        Node<T> *n = self.head;
        while (n) {
                Node<T> *next = n.next;
                listValueFree(n.value);
                mem.free(n);
                n = next;
        }
        self.head = null;
        self.tail = null;
        self.len = 0;
}

/* The same as clear(): for `scoped` and `defer l.free();` */
pub void List<T>.free() {
        self.clear();
}

/* other's nodes moved to the end of this list (no copying); other is
 * left empty */
pub void List<T>.mergeAppend(List<T> *other) {
        if (!other.head) { return; }
        if (self.tail) {
                self.tail.next = other.head;
                other.head.prev = self.tail;
        } else {
                self.head = other.head;
        }
        self.tail = other.tail;
        self.len += other.len;
        other.head = null;
        other.tail = null;
        other.len = 0;
}

/* other's nodes moved to the front of this list; other is left empty */
pub void List<T>.mergePrepend(List<T> *other) {
        if (!other.head) { return; }
        if (self.head) {
                other.tail.next = self.head;
                self.head.prev = other.tail;
        } else {
                self.tail = other.tail;
        }
        self.head = other.head;
        self.len += other.len;
        other.head = null;
        other.tail = null;
        other.len = 0;
}
