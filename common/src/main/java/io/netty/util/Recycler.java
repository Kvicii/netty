/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.util;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.ObjectPool;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import static io.netty.util.internal.MathUtil.safeFindNextPositivePowerOfTwo;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * Light-weight object pool based on a thread-local stack.
 * 轻量级对象池--需要根据场景分析 对象较少时使用就不划算
 * 解决频繁创建对象 并且对象可能非常大 Recycler的实现依赖于 {@link FastThreadLocal}
 *
 * @param <T> the type of the pooled object
 */
public abstract class Recycler<T> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Recycler.class);

    @SuppressWarnings("rawtypes")
    private static final Handle NOOP_HANDLE = object -> {
        // NOOP
    };
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(Integer.MIN_VALUE);
    private static final int OWN_THREAD_ID = ID_GENERATOR.getAndIncrement();
    private static final int DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD = 4 * 1024; // Use 4k instances as default.
    private static final int DEFAULT_MAX_CAPACITY_PER_THREAD;
    private static final int INITIAL_CAPACITY;
    private static final int MAX_SHARED_CAPACITY_FACTOR;
    private static final int MAX_DELAYED_QUEUES_PER_THREAD;
    private static final int LINK_CAPACITY;
    private static final int RATIO;
    private static final int DELAYED_QUEUE_RATIO;

    static {
        // In the future, we might have different maxCapacity for different object types.
        // e.g. io.netty.recycler.maxCapacity.writeTask
        //      io.netty.recycler.maxCapacity.outboundBuffer
        int maxCapacityPerThread = SystemPropertyUtil.getInt("io.netty.recycler.maxCapacityPerThread",
                SystemPropertyUtil.getInt("io.netty.recycler.maxCapacity", DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD));
        if (maxCapacityPerThread < 0) {
            maxCapacityPerThread = DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD;
        }

        DEFAULT_MAX_CAPACITY_PER_THREAD = maxCapacityPerThread;

        MAX_SHARED_CAPACITY_FACTOR = max(2,
                SystemPropertyUtil.getInt("io.netty.recycler.maxSharedCapacityFactor",
                        2));

        MAX_DELAYED_QUEUES_PER_THREAD = max(0,
                SystemPropertyUtil.getInt("io.netty.recycler.maxDelayedQueuesPerThread",
                        // We use the same value as default EventLoop number
                        NettyRuntime.availableProcessors() * 2));

        LINK_CAPACITY = safeFindNextPositivePowerOfTwo(
                max(SystemPropertyUtil.getInt("io.netty.recycler.linkCapacity", 16), 16));

        // By default we allow one push to a Recycler for each 8th try on handles that were never recycled before.
        // This should help to slowly increase the capacity of the recycler while not be too sensitive to allocation
        // bursts.
        RATIO = max(0, SystemPropertyUtil.getInt("io.netty.recycler.ratio", 8));
        DELAYED_QUEUE_RATIO = max(0, SystemPropertyUtil.getInt("io.netty.recycler.delayedQueue.ratio", RATIO));

        INITIAL_CAPACITY = min(DEFAULT_MAX_CAPACITY_PER_THREAD, 256);

        if (logger.isDebugEnabled()) {
            if (DEFAULT_MAX_CAPACITY_PER_THREAD == 0) {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: disabled");
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: disabled");
                logger.debug("-Dio.netty.recycler.linkCapacity: disabled");
                logger.debug("-Dio.netty.recycler.ratio: disabled");
                logger.debug("-Dio.netty.recycler.delayedQueue.ratio: disabled");
            } else {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: {}", DEFAULT_MAX_CAPACITY_PER_THREAD);
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: {}", MAX_SHARED_CAPACITY_FACTOR);
                logger.debug("-Dio.netty.recycler.linkCapacity: {}", LINK_CAPACITY);
                logger.debug("-Dio.netty.recycler.ratio: {}", RATIO);
                logger.debug("-Dio.netty.recycler.delayedQueue.ratio: {}", DELAYED_QUEUE_RATIO);
            }
        }
    }
    // 默认值4 * 1024
    private final int maxCapacityPerThread;
    // 默认值2
    private final int maxSharedCapacityFactor;
    private final int interval;
    // 默认值2倍CPU核数
    private final int maxDelayedQueuesPerThread;
    // 默认值8
    private final int delayedQueueInterval;

    private final FastThreadLocal<Stack<T>> threadLocal = new FastThreadLocal<Stack<T>>() {
        /**
         * 每个Thread和一个Stack进行唯一绑定
         *
         * @return
         */
        @Override
        protected Stack<T> initialValue() {
            return new Stack<>(Recycler.this, Thread.currentThread(), maxCapacityPerThread, maxSharedCapacityFactor,
                    interval, maxDelayedQueuesPerThread, delayedQueueInterval);
        }

        @Override
        protected void onRemoval(Stack<T> value) {
            // Let us remove the WeakOrderQueue from the WeakHashMap directly if its safe to remove some overhead
            if (value.threadRef.get() == Thread.currentThread()) {
                if (DELAYED_RECYCLED.isSet()) {
                    DELAYED_RECYCLED.get().remove(value);
                }
            }
        }
    };

    protected Recycler() {
        // DEFAULT_MAX_CAPACITY_PER_THREAD默认值 4 * 1024
        this(DEFAULT_MAX_CAPACITY_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread) {
        // MAX_SHARED_CAPACITY_FACTOR默认值2
        this(maxCapacityPerThread, MAX_SHARED_CAPACITY_FACTOR);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor) {
        // RATIO默认值是8
        // MAX_DELAYED_QUEUES_PER_THREAD默认值是2倍CPU核数
        this(maxCapacityPerThread, maxSharedCapacityFactor, RATIO, MAX_DELAYED_QUEUES_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread) {
        // DELAYED_QUEUE_RATIO默认值是8
        this(maxCapacityPerThread, maxSharedCapacityFactor, ratio, maxDelayedQueuesPerThread,
                DELAYED_QUEUE_RATIO);
    }

    /**
     * Recycler构造函数
     *
     * @param maxCapacityPerThread      4 * 1024
     * @param maxSharedCapacityFactor   2
     * @param ratio                     8
     * @param maxDelayedQueuesPerThread 2倍CPU核数
     * @param delayedQueueRatio         8
     */
    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread, int delayedQueueRatio) {
        interval = max(0, ratio);
        delayedQueueInterval = max(0, delayedQueueRatio);
        if (maxCapacityPerThread <= 0) {
            this.maxCapacityPerThread = 0;
            this.maxSharedCapacityFactor = 1;
            this.maxDelayedQueuesPerThread = 0;
        } else {
            this.maxCapacityPerThread = maxCapacityPerThread;
            this.maxSharedCapacityFactor = max(1, maxSharedCapacityFactor);
            this.maxDelayedQueuesPerThread = max(0, maxDelayedQueuesPerThread);
        }
    }

    @SuppressWarnings("unchecked")
    public final T get() {
        // 没有开启池化直接new
        if (maxCapacityPerThread == 0) {    // 对象池中不会容纳任何对象
            return newObject((Handle<T>) NOOP_HANDLE);
        }
        // 1.获取当前线程的Stack
        Stack<T> stack = threadLocal.get();
        // 2.从Stack获取Handle对象
        DefaultHandle<T> handle = stack.pop();
        if (handle == null) {   // 对象池池里没有新建一个Handle
            // 一个Handle和一个Stack绑定
            // 一个Handle和一个value绑定
            handle = stack.newHandle();
            handle.value = newObject(handle);
        }
        return (T) handle.value;
    }

    /**
     * @deprecated use {@link Handle#recycle(Object)}.
     */
    @Deprecated
    public final boolean recycle(T o, Handle<T> handle) {
        if (handle == NOOP_HANDLE) {
            return false;
        }

        DefaultHandle<T> h = (DefaultHandle<T>) handle;
        if (h.stack.parent != this) {
            return false;
        }

        h.recycle(o);
        return true;
    }

    final int threadLocalCapacity() {
        return threadLocal.get().elements.length;
    }

    final int threadLocalSize() {
        return threadLocal.get().size;
    }

    protected abstract T newObject(Handle<T> handle);

    public interface Handle<T> extends ObjectPool.Handle<T> {
    }

    @SuppressWarnings("unchecked")
    private static final class DefaultHandle<T> implements Handle<T> {
        private static final AtomicIntegerFieldUpdater<DefaultHandle<?>> LAST_RECYCLED_ID_UPDATER;
        static {
            AtomicIntegerFieldUpdater<?> updater = AtomicIntegerFieldUpdater.newUpdater(
                    DefaultHandle.class, "lastRecycledId");
            LAST_RECYCLED_ID_UPDATER = (AtomicIntegerFieldUpdater<DefaultHandle<?>>) updater;
        }

        volatile int lastRecycledId;
        int recycleId;

        boolean hasBeenRecycled;

        Stack<?> stack;
        Object value;

        DefaultHandle(Stack<?> stack) {
            this.stack = stack;
        }

        /**
         * 对象回收
         *
         * @param object
         */
        @Override
        public void recycle(Object object) {
            if (object != value) {
                throw new IllegalArgumentException("object does not belong to handle");
            }

            Stack<?> stack = this.stack;
            if (lastRecycledId != recycleId || stack == null) {
                throw new IllegalStateException("recycled already");
            }
            // 将使用完的对象归还到对象池 涉及同线程回收 | 异线程回收
            stack.push(this);
        }

        public boolean compareAndSetLastRecycledId(int expectLastRecycledId, int updateLastRecycledId) {
            // Use "weak…" because we do not need synchronize-with ordering, only atomicity.
            // Also, spurious failures are fine, since no code should rely on recycling for correctness.
            return LAST_RECYCLED_ID_UPDATER.weakCompareAndSet(this, expectLastRecycledId, updateLastRecycledId);
        }
    }

    /**
     * 每个线程都有一个Map 对于同一个线程而言 不同的Stack对应了不同的WeakOrderQueue
     * 例:
     *  当前有3个线程, 线程1 2 3
     *  线程1创建的对象有可能到线程3中进行回收
     *  线程2创建的对象有可能到线程3中进行回收
     *  那么对于线程3的 DELAYED_RECYCLED 它的Map的size就是2 即
     *      线程1的Stack, 线程1的WeakOrderQueue
     *      线程2的Stack, 线程2的WeakOrderQueue
     */
    private static final FastThreadLocal<Map<Stack<?>, WeakOrderQueue>> DELAYED_RECYCLED =
            new FastThreadLocal<Map<Stack<?>, WeakOrderQueue>>() {
                @Override
                protected Map<Stack<?>, WeakOrderQueue> initialValue() {
                    return new WeakHashMap<>();
                }
            };

    // a queue that makes only moderate guarantees about visibility: items are seen in the correct order,
    // but we aren't absolutely guaranteed to ever see anything at all, thereby keeping the queue cheap to maintain
    private static final class WeakOrderQueue extends WeakReference<Thread> {

        static final WeakOrderQueue DUMMY = new WeakOrderQueue();

        // Let Link extend AtomicInteger for intrinsics. The Link itself will be used as writerIndex.
        @SuppressWarnings("serial")
        static final class Link extends AtomicInteger {
            // LINK_CAPACITY默认值16
            // 一个Link对应多个Handle的好处是不用每次判断线程2能否回收线程1的对象 只需要判断Link中有空的Handle 就可以把Handle放入
            final DefaultHandle<?>[] elements = new DefaultHandle[LINK_CAPACITY];

            int readIndex;
            // 指向下一个Link(WeakOrderQueue)
            Link next;
        }

        // Its important this does not hold any reference to either Stack or WeakOrderQueue.
        private static final class Head {
            private final AtomicInteger availableSharedCapacity;

            Link link;

            Head(AtomicInteger availableSharedCapacity) {
                this.availableSharedCapacity = availableSharedCapacity;
            }

            /**
             * Reclaim all used space and also unlink the nodes to prevent GC nepotism.
             */
            void reclaimAllSpaceAndUnlink() {
                Link head = link;
                link = null;
                int reclaimSpace = 0;
                while (head != null) {
                    reclaimSpace += LINK_CAPACITY;
                    Link next = head.next;
                    // Unlink to help GC and guard against GC nepotism.
                    head.next = null;
                    head = next;
                }
                if (reclaimSpace > 0) {
                    reclaimSpace(reclaimSpace);
                }
            }

            private void reclaimSpace(int space) {
                availableSharedCapacity.addAndGet(space);
            }

            void relink(Link link) {
                // 已经释放掉了一个Link 后续可以继续往这个WeakOrderQueue中加入Link
                reclaimSpace(LINK_CAPACITY);
                this.link = link;
            }

            /**
             * Creates a new {@link} and returns it if we can reserve enough space for it, otherwise it
             * returns {@code null}.
             */
            Link newLink() {
                return reserveSpaceForLink(availableSharedCapacity) ? new Link() : null;
            }

            /**
             * Stack允许外部线程缓存多少个对象
             *
             * @param availableSharedCapacity
             * @return
             */
            static boolean reserveSpaceForLink(AtomicInteger availableSharedCapacity) {
                for (; ; ) {
                    int available = availableSharedCapacity.get();
                    if (available < LINK_CAPACITY) {
                        return false;
                    }
                    if (availableSharedCapacity.compareAndSet(available, available - LINK_CAPACITY)) {
                        return true;
                    }
                }
            }
        }

        // chain of data items
        private final Head head;
        private Link tail;
        // pointer to another queue of delayed items for the same stack
        private WeakOrderQueue next;
        private final int id = ID_GENERATOR.getAndIncrement();
        private final int interval;
        private int handleRecycleCount;

        private WeakOrderQueue() {
            super(null);
            head = new Head(null);
            interval = 0;
        }

        /**
         * 每个WeakOrderQueue中由Link(每个Link中都是一个Handle数组)串联起单向链表
         *
         * @param stack
         * @param thread
         */
        private WeakOrderQueue(Stack<?> stack, Thread thread) {
            super(thread);
            // 指向最后一个Link
            tail = new Link();

            // Its important that we not store the Stack itself in the WeakOrderQueue as the Stack also is used in
            // the WeakHashMap as key. So just store the enclosed AtomicInteger which should allow to have the
            // Stack itself GCed.
            // 指向第一个Link
            head = new Head(stack.availableSharedCapacity);
            head.link = tail;
            interval = stack.delayedQueueInterval;
            handleRecycleCount = interval; // Start at interval so the first one will be recycled.
        }

        static WeakOrderQueue newQueue(Stack<?> stack, Thread thread) {
            // We allocated a Link so reserve the space
            // Stack代表的线程是否还能分配 LINK_CAPACITY 大小的空间 如果不能分配直接返回null
            if (!Head.reserveSpaceForLink(stack.availableSharedCapacity)) {
                return null;
            }
            // 如果能分配直接构造一个WeakOrderQueue
            final WeakOrderQueue queue = new WeakOrderQueue(stack, thread);
            // Done outside of the constructor to ensure WeakOrderQueue.this does not escape the constructor and so
            // may be accessed while its still constructed.
            stack.setHead(queue);

            return queue;
        }

        WeakOrderQueue getNext() {
            return next;
        }

        void setNext(WeakOrderQueue next) {
            assert next != this;
            this.next = next;
        }

        void reclaimAllSpaceAndUnlink() {
            head.reclaimAllSpaceAndUnlink();
            next = null;
        }

        void add(DefaultHandle<?> handle) {
            if (!handle.compareAndSetLastRecycledId(0, id)) {
                // Separate threads could be racing to add the handle to each their own WeakOrderQueue.
                // We only add the handle to the queue if we win the race and observe that lastRecycledId is zero.
                return;
            }

            // While we also enforce the recycling ratio when we transfer objects from the WeakOrderQueue to the Stack
            // we better should enforce it as well early. Missing to do so may let the WeakOrderQueue grow very fast
            // without control
            if (!handle.hasBeenRecycled) {
                if (handleRecycleCount < interval) {
                    handleRecycleCount++;
                    // Drop the item to prevent from recycling too aggressively.
                    return;
                }
                handleRecycleCount = 0;
            }
            // 获取WeakOrderQueue的tail节点
            Link tail = this.tail;
            int writeIndex;
            // 获取tail(Link)的长度
            if ((writeIndex = tail.get()) == LINK_CAPACITY) {   // tail表示的Link已经不可写了
                // 尝试再创建一个Link
                Link link = head.newLink();
                if (link == null) { // 如果不能创建 直接return
                    // Drop it.
                    return;
                }
                // We allocate a Link so reserve the space
                this.tail = tail = tail.next = link;
                // 获取写指针(tail的长度)
                writeIndex = tail.get();
            }
            // 把Handle追加到tail代表的Link
            tail.elements[writeIndex] = handle;
            // 把Handle的Stack赋值为null 此时这个Handle已经不属于这个Stack了
            handle.stack = null;
            // we lazy set to ensure that setting stack to null appears before we unnull it in the owning thread;
            // this also means we guarantee visibility of an element in the queue if we see the index updated
            // 更改写指针 表明下一次从writeIndex + 1个位置开始写
            tail.lazySet(writeIndex + 1);
        }

        boolean hasFinalData() {
            return tail.readIndex != tail.get();
        }

        // transfer as many items as we can from this queue to the stack, returning true if any were transferred
        @SuppressWarnings("rawtypes")
        boolean transfer(Stack<?> dst) {
            // 获取head节点
            Link head = this.head.link;
            if (head == null) { // 如果head节点不存在 说明WeakOrderQueue不存在Link
                return false;
            }

            if (head.readIndex == LINK_CAPACITY) {  // 读指针指向了Link大小的那个位置 说明当前Link中所有的对象都已被取走
                if (head.next == null) {    // WeakOrderQueue中已经没有Link return false
                    return false;
                }
                // 将head指向下一个节点
                head = head.next;
                this.head.relink(head);
            }

            // 可以从srcStart索引取对象
            final int srcStart = head.readIndex;
            // 当前Link一共有多少对象
            int srcEnd = head.get();
            // 当前Link需要传输到Stack的对象数
            final int srcSize = srcEnd - srcStart;
            if (srcSize == 0) { // 不需要传输 直接返回
                return false;
            }

            // expectedCapacity = 当前Stack的大小 + 当前Link需要传输到Stack的对象数
            final int dstSize = dst.size;
            final int expectedCapacity = dstSize + srcSize;
            // 需要的容量 > Stack的底层数组大小
            if (expectedCapacity > dst.elements.length) {
                // 进行扩容
                final int actualCapacity = dst.increaseCapacity(expectedCapacity);
                // srcEnd不能超过srcStart + actualCapacity - dstSize
                // actualCapacity - dstSize 表示 当前Link需要传输到Stack的对象数
                // 在和srcStart相加即得srcEnd的位置
                srcEnd = min(srcStart + actualCapacity - dstSize, srcEnd);
            }

            // 进行传输
            if (srcStart != srcEnd) {
                // 获取待传输Link中的数组
                final DefaultHandle[] srcElems = head.elements;
                // 获取Stack中的数组
                final DefaultHandle[] dstElems = dst.elements;
                int newDstSize = dstSize;
                // 将Link中数组的元素传输到Stack中的数组
                for (int i = srcStart; i < srcEnd; i++) {
                    DefaultHandle<?> element = srcElems[i];
                    if (element.recycleId == 0) {   // 表明对象没有被回收过
                        // 进行赋值表示已经被回收过
                        element.recycleId = element.lastRecycledId;
                    } else if (element.recycleId != element.lastRecycledId) {   // 如果不相等说明已经被其他线程回收
                        throw new IllegalStateException("recycled already");
                    }
                    srcElems[i] = null;

                    if (dst.dropHandle(element)) {  // 判断是否需要把这个Handle对象加入到Stack中
                        // Drop the object.
                        continue;
                    }
                    element.stack = dst;
                    dstElems[newDstSize++] = element;
                }

                // 当前Link已被回收完 && 当前Link后面还有Link
                if (srcEnd == LINK_CAPACITY && head.next != null) {
                    // Add capacity back as the Link is GCed.
                    this.head.relink(head.next);
                }

                // 下次再进行回收时就可以从srcEnd开始
                head.readIndex = srcEnd;
                if (dst.size == newDstSize) {   // 没有向Stack中传输对象 直接返回
                    return false;
                }
                dst.size = newDstSize;  // 更新Stack的大小 表明已经回收过对象
                return true;
            } else {
                // The destination stack is full already.
                // 当前Stack已经满了 直接返回
                return false;
            }
        }
    }

    private static final class Stack<T> {

        // we keep a queue of per-thread queues, which is appended to once only, each time a new thread other
        // than the stack owner recycles: when we run out of items in our stack we iterate this collection
        // to scavenge those that can be reused. this permits us to incur minimal thread synchronisation whilst
        // still recycling all items.
        final Recycler<T> parent;

        // We store the Thread in a WeakReference as otherwise we may be the only ones that still hold a strong
        // Reference to the Thread itself after it died because DefaultHandle will hold a reference to the Stack.
        //
        // The biggest issue is if we do not use a WeakReference the Thread may not be able to be collected at all if
        // the user will store a reference to the DefaultHandle somewhere and never clear this reference (or not clear
        // it in a timely manner).
        // 当前线程
        final WeakReference<Thread> threadRef;
        // 线程1创建的对象能够在其他线程中缓存的最大个数
        final AtomicInteger availableSharedCapacity;
        // 用于跨线程的对象释放
        // 线程1将对象放入elements中 线程2创建一个weakOrderQueue 存放着线程1创建的对象然后到线程2释放的这部分对象
        // 线程1创建的对象最终能释放的线程数是多少 即当前线程创建的对象能在多少个线程中进行缓存
        private final int maxDelayedQueues;
        // Stack的最大大小 默认4 * 1024
        private final int maxCapacity;
        private final int interval;
        private final int delayedQueueInterval;
        // Stack中存储的一系列的对象
        DefaultHandle<?>[] elements;
        int size;
        // 当前一共回收的对象数
        private int handleRecycleCount;
        private WeakOrderQueue cursor, prev;
        private volatile WeakOrderQueue head;

        Stack(Recycler<T> parent, Thread thread, int maxCapacity, int maxSharedCapacityFactor,
              int interval, int maxDelayedQueues, int delayedQueueInterval) {
            this.parent = parent;
            threadRef = new WeakReference<>(thread);
            this.maxCapacity = maxCapacity;
            // 默认是2 * 1024
            availableSharedCapacity = new AtomicInteger(max(maxCapacity / maxSharedCapacityFactor, LINK_CAPACITY));
            elements = new DefaultHandle[min(INITIAL_CAPACITY, maxCapacity)];
            this.interval = interval;
            this.delayedQueueInterval = delayedQueueInterval;
            handleRecycleCount = interval; // Start at interval so the first one will be recycled.
            this.maxDelayedQueues = maxDelayedQueues;
        }

        // Marked as synchronized to ensure this is serialized.
        synchronized void setHead(WeakOrderQueue queue) {
            queue.setNext(head);
            head = queue;
        }

        int increaseCapacity(int expectedCapacity) {
            int newCapacity = elements.length;
            int maxCapacity = this.maxCapacity;
            do {
                newCapacity <<= 1;
            } while (newCapacity < expectedCapacity && newCapacity < maxCapacity);

            newCapacity = min(newCapacity, maxCapacity);
            if (newCapacity != elements.length) {
                elements = Arrays.copyOf(elements, newCapacity);
            }

            return newCapacity;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        DefaultHandle<T> pop() {
            // Stack的对象数
            int size = this.size;
            if (size == 0) {    // 当前线程的Stack没有对象
                if (!scavenge()) {  // 当前线程Stack创建的对象可能跑到其他线程释放 捞对象
                    return null;
                }
                size = this.size;
                if (size <= 0) {
                    // double check, avoid races
                    return null;
                }
            }
            // 从Stack取出一个对象
            size--;
            DefaultHandle ret = elements[size];
            elements[size] = null;
            // As we already set the element[size] to null we also need to store the updated size before we do
            // any validation. Otherwise we may see a null value when later try to pop again without a new element
            // added before.
            this.size = size;

            if (ret.lastRecycledId != ret.recycleId) {
                throw new IllegalStateException("recycled multiple times");
            }
            ret.recycleId = 0;
            ret.lastRecycledId = 0;
            return ret;
        }

        /**
         * 进入其他线程把当前线程Stack创建的对象捞回来
         *
         * @return
         */
        private boolean scavenge() {
            // continue an existing scavenge, if any
            if (scavengeSome()) {   // 如果已经回收到一些对象 return true
                return true;
            }

            // reset our scavenge cursor
            // 如果没有回收到就重置prev指针和cursor指针
            prev = null;
            // 把cursor指针指向头结点 意味着下次回收从头结点开始回收
            cursor = head;
            return false;
        }

        private boolean scavengeSome() {
            WeakOrderQueue prev;
            // 当前需要回收的WeakOrderQueue
            WeakOrderQueue cursor = this.cursor;
            if (cursor == null) {   // 如果cursor为null
                // 重置prev和cursor指针
                prev = null;
                cursor = head;
                if (cursor == null) {   // 如果head节点为null 说明当前Stack已经没有与他关联的一些WeakOrderQueue
                    // 直接return false 说明什么也没有回收
                    return false;
                }
            } else {
                prev = this.prev;
            }

            boolean success = false;
            // 不断的从Stack关联的WeakOrderQueue链表节点中回收对象
            do {
                if (cursor.transfer(this)) {    // 将WeakOrderQueue的对象传输到Stack
                    // 如果回收成功直接break
                    success = true;
                    break;
                }
                WeakOrderQueue next = cursor.getNext();
                if (cursor.get() == null) { // next关联的线程不存在了 进行清理工作
                    // If the thread associated with the queue is gone, unlink it, after
                    // performing a volatile read to confirm there is no data left to collect.
                    // We never unlink the first queue, as we don't want to synchronize on updating the head.
                    if (cursor.hasFinalData()) {    // next表示的WeakOrderQueue中仍然有数据
                        for (; ; ) {    // 将数据传输到Stack transfer方法每次传输WeakOrderQueue中的一个Link 所以需要使用for循环对WeakOrderQueue中的每个链表节点进行传输
                            if (cursor.transfer(this)) {
                                success = true;
                            } else {
                                break;
                            }
                        }
                    }

                    if (prev != null) { // 释放cursor节点
                        // Ensure we reclaim all space before dropping the WeakOrderQueue to be GC'ed.
                        cursor.reclaimAllSpaceAndUnlink();
                        prev.setNext(next);
                    }
                } else {
                    // 变更prev和cursor的指向 相当于指针后移
                    prev = cursor;
                }

                cursor = next;

            } while (cursor != null && !success);

            this.prev = prev;
            this.cursor = cursor;
            return success;
        }

        void push(DefaultHandle<?> item) {
            Thread currentThread = Thread.currentThread();
            // 判断是同线程还是异线程
            if (threadRef.get() == currentThread) { // 同线程回收对象
                // The current Thread is the thread that belongs to the Stack, we can try to push the object now.
                pushNow(item);
            } else {    // 异线程回收对象
                // The current Thread is not the one that belongs to the Stack
                // (or the Thread that belonged to the Stack was collected already), we need to signal that the push
                // happens later.
                pushLater(item, currentThread);
            }
        }

        /**
         * 同线程回收对象
         *
         * @param item
         */
        private void pushNow(DefaultHandle<?> item) {
            // 首次回收recycleId和lastRecycleId都是0
            if (item.recycleId != 0 || !item.compareAndSetLastRecycledId(0, OWN_THREAD_ID)) {
                throw new IllegalStateException("recycled already");
            }
            item.recycleId = OWN_THREAD_ID;

            int size = this.size;
            // 判断Stack存放元素数量是否大于等于maxCapacity 如果达到上限了直接return(相当于扔掉该item对象)
            if (size >= maxCapacity || dropHandle(item)) {
                // Hit the maximum capacity or should drop - drop the possibly youngest object.
                return;
            }
            // size已经达到Stack中数组的上限 扩容迁移
            if (size == elements.length) {
                elements = Arrays.copyOf(elements, min(size << 1, maxCapacity));
            }
            // 放入元素
            elements[size] = item;
            this.size = size + 1;
        }

        /**
         * 异线程回收对象
         *
         * @param item
         * @param thread
         */
        private void pushLater(DefaultHandle<?> item, Thread thread) {
            if (maxDelayedQueues == 0) {
                // We don't support recycling across threads and should just drop the item on the floor.
                return;
            }

            // we don't want to have a ref to the queue as the value in our weak map
            // so we null it out; to ensure there are no races with restoring it later
            // we impose a memory ordering here (no-op on x86)
            // 将设执行该方法的是线程2 从线程2程获取Map对象
            Map<Stack<?>, WeakOrderQueue> delayedRecycled = DELAYED_RECYCLED.get();
            // 1.this代表的是其他线程(如线程1) 从线程1中获取到WeakOrderQueue
            WeakOrderQueue queue = delayedRecycled.get(this);
            if (queue == null) {    // queue是null 说明线程2之前从未回收过线程1的对象
                if (delayedRecycled.size() >= maxDelayedQueues) {   // 线程2回收过的线程的个数 >= maxDelayedQueues 说明线程2已经不能再回收其他线程的对象了
                    // Add a dummy queue so we know we should drop the object
                    // Map中存入对线程1的DUMMY标记
                    delayedRecycled.put(this, WeakOrderQueue.DUMMY);
                    return;
                }
                // Check if we already reached the maximum number of delayed queues and if we can allocate at all.
                // 创建线程1的WeakOrderQueue
                if ((queue = newWeakOrderQueue(thread)) == null) {
                    // drop object
                    return;
                }
                // 将线程1的Stack与WeakOrderQueue做绑定
                delayedRecycled.put(this, queue);
            } else if (queue == WeakOrderQueue.DUMMY) { // 如果WeakOrderQueue是一个DUMMY标记 直接返回
                // drop object
                return;
            }
            // 将对象追加到WeakOrderQueue
            queue.add(item);
        }

        /**
         * Allocate a new {@link WeakOrderQueue} or return {@code null} if not possible.
         * 为thread线程分配一个在Stack代表的线程对应的WeakOrderQueue
         */
        private WeakOrderQueue newWeakOrderQueue(Thread thread) {
            return WeakOrderQueue.newQueue(this, thread);
        }

        /**
         * 判断是否需要把这个Handle对象加入到Stack中 用于控制回收频率
         *
         * @param handle
         * @return
         */
        boolean dropHandle(DefaultHandle<?> handle) {
            if (!handle.hasBeenRecycled) {  // 如果对象之前没有回收过
                if (handleRecycleCount < interval) {    // 当前为止回收的对象数 < interval
                    handleRecycleCount++;
                    // Drop the object.
                    return true;
                }
                handleRecycleCount = 0;
                handle.hasBeenRecycled = true;
            }
            return false;
        }

        DefaultHandle<T> newHandle() {
            return new DefaultHandle<>(this);
        }
    }
}
