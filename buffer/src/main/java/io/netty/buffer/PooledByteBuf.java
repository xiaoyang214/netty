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

import io.netty.util.Recycler;
import io.netty.util.Recycler.Handle;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

abstract class PooledByteBuf<T> extends AbstractReferenceCountedByteBuf {

    /**
     * Light-weight object pool based on a thread-local stack.
     * Recycle 处理器，用于回收当前对象
     */
    private final Recycler.Handle<PooledByteBuf<T>> recyclerHandle;
    /**
     * chunk 对象
     */
    protected PoolChunk<T> chunk;
    /**
     * 从 Chunk 对象中分配的内存块所处的位置
     */
    protected long handle;
    /**
     * 内存空间，更具泛型为指定的类型
     *  1) PooledDirectByteBuf 和 PooledUnsafeDirectByteBuf 为 ByteBuffer
     *  2) PooledHeapByteBuf 和 PooledUnsafeHeapByteBuf 为 byte[]
     *
     * 因为 memory 属性，可以被多个 ByteBuf 使用。每个 ByteBuf 使用范围为 [offset, maxLength)
     */
    protected T memory;
    /**
     * 偏移量 memory 的开始位置
     * {@link #idx(int)}
     */
    protected int offset;
    /**
     * 容量，目前使用 memory 的长度
     * {@link #capacity()}
     */
    protected int length;
    /**
     * 最大使用 memory 的长度
     */
    int maxLength;
    /**
     * {@link PoolThreadCache} 可伸缩内存 jemalloc
     */
    PoolThreadCache cache;
    /**
     * 临时的 jdk {@link ByteBuffer}, 通过 {@link #internalNioBuffer()} 方法操作
     */
    ByteBuffer tmpNioBuf;
    /**
     * ByteBuf 分配器
     */
    private ByteBufAllocator allocator;

    @SuppressWarnings("unchecked")
    protected PooledByteBuf(Recycler.Handle<? extends PooledByteBuf<T>> recyclerHandle, int maxCapacity) {
        super(maxCapacity);
        this.recyclerHandle = (Handle<PooledByteBuf<T>>) recyclerHandle;
    }

    void init(PoolChunk<T> chunk, ByteBuffer nioBuffer,
              long handle, int offset, int length, int maxLength, PoolThreadCache cache) {
        init0(chunk, nioBuffer, handle, offset, length, maxLength, cache);
    }

    void initUnpooled(PoolChunk<T> chunk, int length) {
        init0(chunk, null, 0, chunk.offset, length, length, null);
    }

    /**
     * init: nioBuffer, handler
     * initUnpooled: 基于 UnPooled 的操作 nioBuffer = null, handler = 0, maxLength == length
     */
    private void init0(PoolChunk<T> chunk, ByteBuffer nioBuffer,
                       long handle, int offset, int length, int maxLength, PoolThreadCache cache) {
        assert handle >= 0;
        assert chunk != null;

        this.chunk = chunk;
        memory = chunk.memory;
        allocator = chunk.arena.parent;

        tmpNioBuf = nioBuffer;
        this.cache = cache;
        this.handle = handle;
        this.offset = offset;
        this.length = length;
        this.maxLength = maxLength;
    }

    /**
     * Method must be called before reuse this {@link PooledByteBufAllocator}
     */
    final void reuse(int maxCapacity) {
        maxCapacity(maxCapacity);
        setRefCnt(1);
        setIndex0(0, 0);
        discardMarks();
    }

    @Override
    public final int capacity() {
        return length;
    }

    @Override
    public final ByteBuf capacity(int newCapacity) {
        checkNewCapacity(newCapacity);
        // 非池化的 PoolChunk，除非相等，否则就会根据实际的 newCapacity 进行容量调整, initUnpooled 中 length == maxLength
        // If the request capacity does not require reallocation, just update the length of the memory.
        if (chunk.unpooled) {
            // 如果相等, 直接返回, 不需要扩容缩容
            if (newCapacity == length) {
                return this;
            }
        } else {
            // 如果大于当前容量
            if (newCapacity > length) {
                // 扩容
                if (newCapacity <= maxLength) {
                    length = newCapacity;
                    return this;
                }
            } else if (newCapacity < length) {
                // 如果新容量小于当前容量，大于最大容量的一半，对容量进行调整，否则不调整容量(猜测调整容量获取的效果不大)，进行重新内容分配
                if (newCapacity > maxLength >>> 1) {
                    if (maxLength <= 512) {
                        // xiaoyang 2019/4/9: 猜测 netty 的最小 SubPage 是 16, 如果小于等于16, 无法进行缩容
                        if (newCapacity > maxLength - 16) {
                            length = newCapacity;
                            // 重新设置读写指针
                            setIndex(Math.min(readerIndex(), newCapacity), Math.min(writerIndex(), newCapacity));
                            return this;
                        }
                    } else { // > 512 (i.e. >= 1024)
                        length = newCapacity;
                        setIndex(Math.min(readerIndex(), newCapacity), Math.min(writerIndex(), newCapacity));
                        return this;
                    }
                }
            } else {
                return this;
            }
        }

        // Reallocation required.
        // 重新分配内存，将数据复制进去，并释放老的内存
        chunk.arena.reallocate(this, newCapacity, true);
        return this;
    }

    @Override
    public final ByteBufAllocator alloc() {
        return allocator;
    }

    // xiaoyang 2019/4/9: https://www.bysocket.com/archives/615/%E6%B7%B1%E5%85%A5%E6%B5%85%E5%87%BA%EF%BC%9A-%E5%A4%A7%E5%B0%8F%E7%AB%AF%E6%A8%A1%E5%BC%8F
    @Override
    public final ByteOrder order() {
        return ByteOrder.BIG_ENDIAN;
    }

    @Override
    public final ByteBuf unwrap() {
        return null;
    }

    /**
     * 复制一个部分，不改变源数据的读写指正
     */
    @Override
    public final ByteBuf retainedDuplicate() {
        return PooledDuplicatedByteBuf.newInstance(this, this, readerIndex(), writerIndex());
    }

    /**
     * 保留分片，返回可读字节，不修改当前缓冲区的读写指针
     */
    @Override
    public final ByteBuf retainedSlice() {
        final int index = readerIndex();
        return retainedSlice(index, writerIndex() - index);
    }

    @Override
    public final ByteBuf retainedSlice(int index, int length) {
        return PooledSlicedByteBuf.newInstance(this, this, index, length);
    }

    protected final ByteBuffer internalNioBuffer() {
        ByteBuffer tmpNioBuf = this.tmpNioBuf;
        if (tmpNioBuf == null) {
            this.tmpNioBuf = tmpNioBuf = newInternalNioBuffer(memory);
        }
        return tmpNioBuf;
    }

    protected abstract ByteBuffer newInternalNioBuffer(T memory);

    @Override
    protected final void deallocate() {
        // 当引用计数器为 0 的时候，调用次方法
        if (handle >= 0) {
            final long handle = this.handle;
            this.handle = -1;
            memory = null;
            chunk.arena.free(chunk, tmpNioBuf, handle, maxLength, cache);
            tmpNioBuf = null;
            chunk = null;
            recycle();
        }
    }

    private void recycle() {
        recyclerHandle.recycle(this);
    }

    protected final int idx(int index) {
        return offset + index;
    }
}
