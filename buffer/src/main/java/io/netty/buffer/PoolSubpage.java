/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.buffer;

final class PoolSubpage<T> implements PoolSubpageMetric {
    /**
     * 所属 PoolChunk 对象
     */
    final PoolChunk<T> chunk;
    /**
     * memory map 的索引
     */
    private final int memoryMapIdx;
    /**
     * chunk 中的偏移字节量
     */
    private final int runOffset;
    /**
     * Page 的大小
     */
    private final int pageSize;
    /**
     * 位图，最大的数组 size 为 8 (8192 >>> 10)
     * 使用数组，是因为long类型的位图只能容纳 64 个，subpage所能分配的超过了 64 个，最大可以 8192 / 16 = 512 个
     * 512 / 64 = 8
     */
    private final long[] bitmap;

    /**
     * 前一个，后一个 PoolSubpage，双向链表
     */
    PoolSubpage<T> prev;
    PoolSubpage<T> next;

    /**
     * 是否还未被销毁
     */
    boolean doNotDestroy;
    /**
     * 元素的大小
     */
    int elemSize;
    /**
     * subpage 的数量
     */
    private int maxNumElems;
    /**
     * 位图长度
     */
    private int bitmapLength;
    /**
     * 下一个可分配的 subpage 的位置
     */
    private int nextAvail;
    /**
     * 剩余可用 subpage 的数量
     */
    private int numAvail;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /**
     * Special constructor that creates a linked list head
     * 用来创建一个链表的头部的构造函数
     */
    PoolSubpage(int pageSize) {
        chunk = null;
        memoryMapIdx = -1;
        runOffset = -1;
        elemSize = -1;
        this.pageSize = pageSize;
        bitmap = null;
    }

    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int memoryMapIdx, int runOffset, int pageSize, int elemSize) {
        this.chunk = chunk;
        this.memoryMapIdx = memoryMapIdx;
        this.runOffset = runOffset;
        this.pageSize = pageSize;
        // 最大为 8 个容量的数组
        bitmap = new long[pageSize >>> 10]; // pageSize / 16 / 64
        init(head, elemSize);
    }

    void init(PoolSubpage<T> head, int elemSize) {
        // 设置为没有销毁
        doNotDestroy = true;
        // 每个元素所占的大小
        this.elemSize = elemSize;
        if (elemSize != 0) {
            // 元素最大个数 pageSize / elemSize
            maxNumElems = numAvail = pageSize / elemSize;
            // 从第一个开始可用
            nextAvail = 0;
            // 右移 6 位， / 64
            bitmapLength = maxNumElems >>> 6;
            if ((maxNumElems & 63) != 0) { // 未整除，补1
                bitmapLength ++;
            }
            // 初始化位图数组
            for (int i = 0; i < bitmapLength; i ++) {
                bitmap[i] = 0;
            }
        }
        // 添加到双向链表中, 当前节点插入到 head 后面
        addToPool(head);
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     * 返回分配subpage在位图中的索引
     */
    long allocate() {
        // 应该不会出现这种情况
        if (elemSize == 0) {
            return toHandle(0);
        }
        // 无可用的subpage数量或者已经被销毁了
        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }
        // 获取下一个可用的在整个位图中的索引
        final int bitmapIdx = getNextAvail();
        // 获得下一个可用的 Subpage 在 bitmap 中数组的位置 bitmapIdx / 64
        int q = bitmapIdx >>> 6;
        // 获得下一个可用的 Subpage 在 bitmap 中数组的位置的第几 bits bitmapIdx % 64
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) == 0;
        // 修改位图为不可用状态，标识已经分配
        bitmap[q] |= 1L << r;
        // 可用数量减一，如果没有了
        if (-- numAvail == 0) {
            // 从池子中移除
            removeFromPool();
        }
        // 返回位图索引
        return toHandle(bitmapIdx);
    }

    /**
     * @return {@code true} if this subpage is in use.
     *         {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        if (elemSize == 0) {
            return true;
        }
        // 获取在位图数组中的位置
        int q = bitmapIdx >>> 6;
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) != 0;
        // 标记为可用
        bitmap[q] ^= 1L << r;
        // 设置下一个可用的节点
        setNextAvail(bitmapIdx);
        // 增加数量
        if (numAvail ++ == 0) {
            // 添加到池子中
            addToPool(head);
            return true;
        }

        if (numAvail != maxNumElems) {
            return true;
        } else {
            // 没有人在使用了
            // Subpage not in use (numAvail == maxNumElems)
            if (prev == next) {
                // 双向链表中，只有左侧的一个节点(当前节点)，不删除
                // Do not remove if this subpage is the only one left in the pool.
                return true;
            }
            // 标记为销毁
            // Remove this subpage from the pool if there are other subpages left in the pool.
            doNotDestroy = false;
            // 从池子中移除
            removeFromPool();
            return false;
        }
    }

    private void addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        this.prev = head;
        this.next = head.next;
        this.next.prev = this;
        head.next = this;
    }

    private void removeFromPool() {
        assert prev != null && next != null;
        prev.next = next;
        next.prev = prev;
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }

    private int getNextAvail() {
        // nextAvail = -1, 当前已经被使用了
        int nextAvail = this.nextAvail;
        if (nextAvail >= 0) {
            this.nextAvail = -1;
            return nextAvail;
        }
        return findNextAvail();
    }

    private int findNextAvail() {
        final long[] bitmap = this.bitmap;
        final int bitmapLength = this.bitmapLength;
        for (int i = 0; i < bitmapLength; i ++) {
            long bits = bitmap[i];
            // ~ 二进制取反 00001111 11110000
            if (~bits != 0) {
                return findNextAvail0(i, bits);
            }
        }
        return -1;
    }

    private int findNextAvail0(int i, long bits) {
        final int maxNumElems = this.maxNumElems;
        // 获取在bitmap中数组的下标 i * 64
        final int baseVal = i << 6;

        for (int j = 0; j < 64; j ++) {
            // 判断当前bit是否未分配
            if ((bits & 1) == 0) {
                int val = baseVal | j;
                if (val < maxNumElems) {
                    return val;
                } else {
                    break;
                }
            }
            //
            bits >>>= 1;
        }
        return -1;
    }

    private long toHandle(int bitmapIdx) {
        // 低 32 位是在内存中的索引，标记所属与哪个chunk中的哪个page，高32位是在位图中的索引，标记page节点中的subpage位置
        // 0x4000000000000000L 解决 bitmapIdx == 0 的冲突
        return 0x4000000000000000L | (long) bitmapIdx << 32 | memoryMapIdx;
    }

    @Override
    public String toString() {
        final boolean doNotDestroy;
        final int maxNumElems;
        final int numAvail;
        final int elemSize;
        synchronized (chunk.arena) {
            if (!this.doNotDestroy) {
                doNotDestroy = false;
                // Not used for creating the String.
                maxNumElems = numAvail = elemSize = -1;
            } else {
                doNotDestroy = true;
                maxNumElems = this.maxNumElems;
                numAvail = this.numAvail;
                elemSize = this.elemSize;
            }
        }

        if (!doNotDestroy) {
            return "(" + memoryMapIdx + ": not in use)";
        }

        return "(" + memoryMapIdx + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
                ", offset: " + runOffset + ", length: " + pageSize + ", elemSize: " + elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        synchronized (chunk.arena) {
            return maxNumElems;
        }
    }

    @Override
    public int numAvailable() {
        synchronized (chunk.arena) {
            return numAvail;
        }
    }

    @Override
    public int elementSize() {
        synchronized (chunk.arena) {
            return elemSize;
        }
    }

    @Override
    public int pageSize() {
        return pageSize;
    }

    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }
}
