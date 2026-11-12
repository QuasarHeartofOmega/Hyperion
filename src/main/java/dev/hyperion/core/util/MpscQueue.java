package dev.hyperion.core.util;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Lock-free очередь Multi-Producer / Single-Consumer (алгоритм Vyukov).
 *
 * <p>В исходной спеке проекта очередь была написана неверно: CAS по head + {@code tail.lazySet}
 * даёт LIFO-порядок, теряет элементы при гонке и возвращает один и тот же элемент дважды.
 * Здесь реализован корректный вариант:
 * <ul>
 *   <li>есть stub-узел, head и tail изначально указывают на него;</li>
 *   <li>производитель делает {@code getAndSet} по head (wait-free) и одним volatile-write
 *       публикует {@code prev.next} — это единственная точка синхронизации;</li>
 *   <li>потребитель (ровно один поток) двигает tail, читая volatile {@code next}.</li>
 * </ul>
 *
 * <p>Гарантии: FIFO, linearizable, без блокировок и без аллокаций на чтении.
 * {@link #size()} — приблизительный (счётчик обновляется не атомарно со связыванием узла),
 * он используется только для мягкого ограничения ёмкости и метрик.
 */
public final class MpscQueue<T> {

    private static final class Node<T> {
        volatile T value;
        volatile Node<T> next;

        Node(T value) {
            this.value = value;
        }
    }

    private final AtomicReference<Node<T>> head;
    /** Трогает только поток-потребитель. */
    private Node<T> tail;
    private final AtomicInteger approxSize = new AtomicInteger();
    private final int capacity;

    public MpscQueue() {
        this(Integer.MAX_VALUE);
    }

    public MpscQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        Node<T> stub = new Node<>(null);
        this.head = new AtomicReference<>(stub);
        this.tail = stub;
        this.capacity = capacity;
    }

    /** Wait-free для производителей. Возвращает false, если мягкий лимит ёмкости исчерпан. */
    public boolean offer(T item) {
        Objects.requireNonNull(item, "item");
        if (approxSize.get() >= capacity) {
            return false;
        }
        Node<T> node = new Node<>(item);
        Node<T> prev = head.getAndSet(node);
        prev.next = node;
        approxSize.incrementAndGet();
        return true;
    }

    /** Только один поток-потребитель. Возвращает null, если очередь пуста. */
    public T poll() {
        Node<T> t = tail;
        Node<T> next = t.next;
        if (next == null) {
            return null;
        }
        T value = next.value;
        next.value = null; // помогаем GC
        tail = next;
        approxSize.decrementAndGet();
        return value;
    }

    /** Перекладывает до {@code limit} элементов в потребителя. Возвращает число обработанных. */
    public int drainTo(Consumer<? super T> consumer, int limit) {
        int n = 0;
        while (n < limit) {
            T item = poll();
            if (item == null) break;
            consumer.accept(item);
            n++;
        }
        return n;
    }

    public boolean isEmpty() {
        return tail.next == null;
    }

    public int size() {
        return Math.max(0, approxSize.get());
    }

    public int capacity() {
        return capacity;
    }

    /** Только для потока-потребителя. */
    public void clear() {
        while (poll() != null) {
            // drain
        }
    }
}
