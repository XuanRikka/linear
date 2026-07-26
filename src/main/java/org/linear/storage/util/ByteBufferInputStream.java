package org.linear.storage.util;

import java.io.InputStream;
import java.nio.ByteBuffer;

/** 将 {@link ByteBuffer} 剩余内容暴露为 {@link InputStream}（移植自参考实现）。 */
public final class ByteBufferInputStream extends InputStream {
    private final ByteBuffer internal;

    public ByteBufferInputStream(ByteBuffer buf) {
        this.internal = buf;
    }

    @Override
    public int available() {
        return this.internal.remaining();
    }

    @Override
    public int read() {
        return this.internal.hasRemaining() ? (this.internal.get() & 0xFF) : -1;
    }

    @Override
    public int read(byte[] bytes, int off, int len) {
        if (len == 0) {
            return 0;
        }
        if (!this.internal.hasRemaining()) {
            return -1;
        }
        len = Math.min(len, this.internal.remaining());
        this.internal.get(bytes, off, len);
        return len;
    }
}
