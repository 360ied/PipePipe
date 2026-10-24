package us.shandian.giga.io;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import org.schabi.newpipe.streams.io.SharpStream;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * @author kapodamy
 */
public class FileStream extends SharpStream {

    private RandomAccessFile source;
    private final boolean readOnly;

    public FileStream(@NonNull File target) throws FileNotFoundException {
        this(target, false);
    }

    public FileStream(@NonNull File target, boolean readOnly) throws FileNotFoundException {
        this.source = new RandomAccessFile(target, readOnly ? "r" : "rw");
        this.readOnly = readOnly;
    }

    public FileStream(@NonNull String path) throws FileNotFoundException {
        this(new File(path), false);
    }

    @Override
    public int read() throws IOException {
        return requireOpen().read();
    }

    private RandomAccessFile requireOpen() throws IOException {
        // Fail closed with IOException (not NPE): a read-after-close is a
        // stream-state error the muxers map to STORAGE/MUXING, not a bug
        // reportable as NullPointerException.
        RandomAccessFile current = source;
        if (current == null) {
            throw new IOException("stream is closed");
        }
        return current;
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    RandomAccessFile getSourceForTest() {
        return source;
    }

    @Override
    public int read(byte[] b) throws IOException {
        return requireOpen().read(b);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return requireOpen().read(b, off, len);
    }

    @Override
    public long skip(long pos) throws IOException {
        // skipBytes takes an int; loop so skips over ~2 GiB don't truncate.
        long remaining = pos;
        long skipped = 0;
        while (remaining > 0) {
            int count = requireOpen().skipBytes((int) Math.min(remaining, Integer.MAX_VALUE));
            if (count <= 0) {
                break;
            }
            skipped += count;
            remaining -= count;
        }
        return skipped;
    }

    @Override
    public long available() {
        try {
            return requireOpen().length() - requireOpen().getFilePointer();
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public void close() {
        // Idempotent by design: close() itself never throws, so it keeps
        // direct access instead of requireOpen().
        if (source == null) return;
        try {
            source.close();
        } catch (IOException err) {
            // nothing to do
        }
        source = null;
    }

    @Override
    public boolean isClosed() {
        return source == null;
    }

    @Override
    public void rewind() throws IOException {
        requireOpen().seek(0);
    }

    @Override
    public boolean canRewind() {
        return true;
    }

    @Override
    public boolean canRead() {
        return true;
    }

    @Override
    public boolean canWrite() {
        return !readOnly;
    }

    @Override
    public boolean canSetLength() {
        return !readOnly;
    }

    @Override
    public boolean canSeek() {
        return true;
    }

    @Override
    public void write(byte value) throws IOException {
        requireOpen().write(value);
    }

    @Override
    public void write(byte[] buffer) throws IOException {
        requireOpen().write(buffer);
    }

    @Override
    public void write(byte[] buffer, int offset, int count) throws IOException {
        requireOpen().write(buffer, offset, count);
    }

    @Override
    public void setLength(long length) throws IOException {
        requireOpen().setLength(length);
    }

    @Override
    public void seek(long offset) throws IOException {
        requireOpen().seek(offset);
    }

    @Override
    public long length() throws IOException {
        return requireOpen().length();
    }
}
