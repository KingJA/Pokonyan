/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sample.kingja.pokonyan;

import android.annotation.Nullable;
import android.annotation.UnsupportedAppUsage;
import android.content.SharedPreferences;
import android.os.FileUtils;
import android.os.Looper;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;
import android.system.StructTimespec;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ExponentiallyBucketedHistogram;
import com.android.internal.util.XmlUtils;

import dalvik.system.BlockGuard;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;

final class SharedPreferencesImpl implements SharedPreferences {
    private static final String TAG = "SharedPreferencesImpl";
    private static final boolean DEBUG = false;
    private static final Object CONTENT = new Object();

    /**
     * If a fsync takes more than {@value #MAX_FSYNC_DURATION_MILLIS} ms, warn
     */
    private static final long MAX_FSYNC_DURATION_MILLIS = 256;

    // Lock ordering rules:
    //  - acquire SharedPreferencesImpl.mLock before EditorImpl.mLock
    //  - acquire mWritingToDiskLock before EditorImpl.mLock

    @UnsupportedAppUsage
    private final File mFile;
    private final File mBackupFile;
    private final int mMode;
    private final Object mLock = new Object();
    private final Object mWritingToDiskLock = new Object();

    @GuardedBy("mLock")
    private Map<String, Object> mMap;
    @GuardedBy("mLock")
    private Throwable mThrowable;

    @GuardedBy("mLock")
    private int mDiskWritesInFlight = 0;

    @GuardedBy("mLock")
    private boolean mLoaded = false;

    @GuardedBy("mLock")
    private StructTimespec mStatTimestamp;

    @GuardedBy("mLock")
    private long mStatSize;

    @GuardedBy("mLock")
    private final WeakHashMap<OnSharedPreferenceChangeListener, Object> mListeners =
            new WeakHashMap<OnSharedPreferenceChangeListener, Object>();

    /**
     * Current memory state (always increasing)
     */
    @GuardedBy("this")
    private long mCurrentMemoryStateGeneration;

    /**
     * Latest memory state that was committed to disk
     */
    @GuardedBy("mWritingToDiskLock")
    private long mDiskStateGeneration;

    /**
     * Time (and number of instances) of file-system sync requests
     */
    @GuardedBy("mWritingToDiskLock")
    private final ExponentiallyBucketedHistogram mSyncTimes = new ExponentiallyBucketedHistogram(16);
    private int mNumSync = 0;

    @UnsupportedAppUsage
    SharedPreferencesImpl(File file, int mode) {
        mFile = file;
        //创建备份文件
        mBackupFile = makeBackupFile(file);
        mMode = mode;
        mLoaded = false;
        mMap = null;
        mThrowable = null;
        //解析xml文件并加载到内存中
        startLoadFromDisk();
    }

    @UnsupportedAppUsage
    private void startLoadFromDisk() {
        //设置mLoad为false表示，xml文件还没加载
        synchronized (mLock) {
            mLoaded = false;
        }
        new Thread("SharedPreferencesImpl-load") {
            public void run() {
                //从磁盘加载到内存 .xml->Map
                loadFromDisk();
            }
        }.start();
    }

    private void loadFromDisk() {
        synchronized (mLock) {
            //如果已经加载好了就跳出避免重复加载
            if (mLoaded) {
                return;
            }
            //备份文件存在则删除源文件，备份文件重命名为源文件
            if (mBackupFile.exists()) {
                mFile.delete();
                mBackupFile.renameTo(mFile);
            }
        }
        // Debugging
        if (mFile.exists() && !mFile.canRead()) {
            Log.w(TAG, "Attempt to read preferences file " + mFile + " without permission");
        }
        Map<String, Object> map = null;
        StructStat stat = null;
        Throwable thrown = null;
        try {
            stat = Os.stat(mFile.getPath());
            if (mFile.canRead()) {
                BufferedInputStream str = null;
                try {
                    str = new BufferedInputStream(
                            new FileInputStream(mFile), 16 * 1024);
                    //把sp的xml文件解析到map
                    map = (Map<String, Object>) XmlUtils.readMapXml(str);
                } catch (Exception e) {
                    Log.w(TAG, "Cannot read " + mFile.getAbsolutePath(), e);
                } finally {
                    IoUtils.closeQuietly(str);
                }
            }
        } catch (ErrnoException e) {
            // An errno exception means the stat failed. Treat as empty/non-existing by
            // ignoring.
        } catch (Throwable t) {
            thrown = t;
        }

        synchronized (mLock) {
            //标示设为已经加载
            mLoaded = true;
            mThrowable = thrown;
            try {
                if (thrown == null) {
                    if (map != null) {
                        //把解析得到的map赋值给全局mMap
                        mMap = map;
                        mStatTimestamp = stat.st_mtim;
                        mStatSize = stat.st_size;
                    } else {
                        mMap = new HashMap<>();
                    }
                }
            } catch (Throwable t) {
                //把异常设为全局异常，则其它方法调用的时候可以做相应处理
                mThrowable = t;
            } finally {
                mLock.notifyAll();
            }
        }
    }

    static File makeBackupFile(File prefsFile) {
        return new File(prefsFile.getPath() + ".bak");
    }

    @UnsupportedAppUsage
    void startReloadIfChangedUnexpectedly() {
        synchronized (mLock) {
            // TODO: wait for any pending writes to disk?
            if (!hasFileChangedUnexpectedly()) {
                return;
            }
            startLoadFromDisk();
        }
    }

    // Has the file changed out from under us?  i.e. writes that
    // we didn't instigate.
    private boolean hasFileChangedUnexpectedly() {
        synchronized (mLock) {
            if (mDiskWritesInFlight > 0) {
                // If we know we caused it, it's not unexpected.
                if (DEBUG) Log.d(TAG, "disk write in flight, not unexpected.");
                return false;
            }
        }

        final StructStat stat;
        try {
            /*
             * Metadata operations don't usually count as a block guard
             * violation, but we explicitly want this one.
             */
            BlockGuard.getThreadPolicy().onReadFromDisk();
            stat = Os.stat(mFile.getPath());
        } catch (ErrnoException e) {
            return true;
        }

        synchronized (mLock) {
            return !stat.st_mtim.equals(mStatTimestamp) || mStatSize != stat.st_size;
        }
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        synchronized (mLock) {
            mListeners.put(listener, CONTENT);
        }
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        synchronized (mLock) {
            mListeners.remove(listener);
        }
    }

    @GuardedBy("mLock")
    private void awaitLoadedLocked() {
        if (!mLoaded) {
            // Raise an explicit StrictMode onReadFromDisk for this
            // thread, since the real read will be in a different
            // thread and otherwise ignored by StrictMode.
            BlockGuard.getThreadPolicy().onReadFromDisk();
        }
        while (!mLoaded) {
            try {
                mLock.wait();
            } catch (InterruptedException unused) {
            }
        }
        if (mThrowable != null) {
            throw new IllegalStateException(mThrowable);
        }
    }

    @Override
    public Map<String, ?> getAll() {
        synchronized (mLock) {
            awaitLoadedLocked();
            //noinspection unchecked
            return new HashMap<String, Object>(mMap);
        }
    }

    @Override
    @Nullable
    public String getString(String key, @Nullable String defValue) {
        synchronized (mLock) {
            awaitLoadedLocked();
            String v = (String) mMap.get(key);
            return v != null ? v : defValue;
        }
    }

    @Override
    @Nullable
    public Set<String> getStringSet(String key, @Nullable Set<String> defValues) {
        synchronized (mLock) {
            awaitLoadedLocked();
            Set<String> v = (Set<String>) mMap.get(key);
            return v != null ? v : defValues;
        }
    }

    @Override
    public int getInt(String key, int defValue) {
        synchronized (mLock) {
            awaitLoadedLocked();
            Integer v = (Integer) mMap.get(key);
            return v != null ? v : defValue;
        }
    }

    @Override
    public long getLong(String key, long defValue) {
        synchronized (mLock) {
            awaitLoadedLocked();
            Long v = (Long) mMap.get(key);
            return v != null ? v : defValue;
        }
    }

    @Override
    public float getFloat(String key, float defValue) {
        synchronized (mLock) {
            awaitLoadedLocked();
            Float v = (Float) mMap.get(key);
            return v != null ? v : defValue;
        }
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        synchronized (mLock) {
            awaitLoadedLocked();
            Boolean v = (Boolean) mMap.get(key);
            return v != null ? v : defValue;
        }
    }

    @Override
    public boolean contains(String key) {
        synchronized (mLock) {
            awaitLoadedLocked();
            return mMap.containsKey(key);
        }
    }

    @Override
    public Editor edit() {
        synchronized (mLock) {
            //  如果执行 edit() 时如果 SharedPreferencesImpl 没有加载完成，就会阻塞
            awaitLoadedLocked();
        }
        return new EditorImpl();
    }

    // Return value from EditorImpl#commitToMemory()
    private static class MemoryCommitResult {
        final long memoryStateGeneration;
        @Nullable
        final List<String> keysModified;
        @Nullable
        final Set<OnSharedPreferenceChangeListener> listeners;
        final Map<String, Object> mapToWriteToDisk;
        final CountDownLatch writtenToDiskLatch = new CountDownLatch(1);

        @GuardedBy("mWritingToDiskLock")
        volatile boolean writeToDiskResult = false;
        boolean wasWritten = false;

        private MemoryCommitResult(long memoryStateGeneration, @Nullable List<String> keysModified,
                                   @Nullable Set<OnSharedPreferenceChangeListener> listeners,
                                   Map<String, Object> mapToWriteToDisk) {
            this.memoryStateGeneration = memoryStateGeneration;
            this.keysModified = keysModified;
            this.listeners = listeners;
            this.mapToWriteToDisk = mapToWriteToDisk;
        }

        void setDiskWriteResult(boolean wasWritten, boolean result) {
            this.wasWritten = wasWritten;
            writeToDiskResult = result;
            writtenToDiskLatch.countDown();
        }
    }

    public final class EditorImpl implements Editor {
        private final Object mEditorLock = new Object();

        @GuardedBy("mEditorLock")
        private final Map<String, Object> mModified = new HashMap<>();

        @GuardedBy("mEditorLock")
        private boolean mClear = false;

        @Override
        public Editor putString(String key, @Nullable String value) {
            synchronized (mEditorLock) {
                //创建一个Map<String, Object> mModified来保存不同类型的数据
                mModified.put(key, value);
                return this;
            }
        }

        @Override
        public Editor putStringSet(String key, @Nullable Set<String> values) {
            synchronized (mEditorLock) {
                mModified.put(key,
                        (values == null) ? null : new HashSet<String>(values));
                return this;
            }
        }

        @Override
        public Editor putInt(String key, int value) {
            synchronized (mEditorLock) {
                mModified.put(key, value);
                return this;
            }
        }

        @Override
        public Editor putLong(String key, long value) {
            synchronized (mEditorLock) {
                mModified.put(key, value);
                return this;
            }
        }

        @Override
        public Editor putFloat(String key, float value) {
            synchronized (mEditorLock) {
                mModified.put(key, value);
                return this;
            }
        }

        @Override
        public Editor putBoolean(String key, boolean value) {
            synchronized (mEditorLock) {
                mModified.put(key, value);
                return this;
            }
        }

        @Override
        public Editor remove(String key) {
            synchronized (mEditorLock) {
                mModified.put(key, this);
                return this;
            }
        }

        @Override
        public Editor clear() {
            synchronized (mEditorLock) {
                mClear = true;
                return this;
            }
        }

        @Override
        public void apply() {
            final long startTime = System.currentTimeMillis();

            //提交到内存
            final MemoryCommitResult mcr = commitToMemory();
            final Runnable awaitCommit = new Runnable() {
                @Override
                public void run() {
                    try {
                        mcr.writtenToDiskLatch.await();
                    } catch (InterruptedException ignored) {
                    }

                    if (DEBUG && mcr.wasWritten) {
                        Log.d(TAG, mFile.getName() + ":" + mcr.memoryStateGeneration
                                + " applied after " + (System.currentTimeMillis() - startTime)
                                + " ms");
                    }
                }
            };

            //往系统队列中发送任务
            QueuedWork.addFinisher(awaitCommit);

            Runnable postWriteRunnable = new Runnable() {
                @Override
                public void run() {
                    awaitCommit.run();
                    //系统队列中删除任务
                    QueuedWork.removeFinisher(awaitCommit);
                }
            };

            //异步保存在磁盘
            SharedPreferencesImpl.this.enqueueDiskWrite(mcr, postWriteRunnable);

            // Okay to notify the listeners before it's hit disk
            // because the listeners should always get the same
            // SharedPreferences instance back, which has the
            // changes reflected in memory.
            notifyListeners(mcr);
        }

        // Returns true if any changes were made
        private MemoryCommitResult commitToMemory() {
            long memoryStateGeneration;
            List<String> keysModified = null;
            Set<OnSharedPreferenceChangeListener> listeners = null;
            Map<String, Object> mapToWriteToDisk;
            synchronized (SharedPreferencesImpl.this.mLock) {
                // We optimistically don't make a deep copy until a memory commit comes in when we're already writing
                // to disk.
                //我们不会做一个深拷贝，直到内存提交，我们准备写到磁盘上
                if (mDiskWritesInFlight > 0) {
                    // We can't modify our mMap as a currently
                    //我们不能马上修改mMap
                    // in-flight write owns it.  Clone it before modifying it.
                    // noinspection unchecked
                    //不能直接对mMap进行修改
                    mMap = new HashMap<String, Object>(mMap);
                }
                //内存里的map形式的sp
                mapToWriteToDisk = mMap;
                mDiskWritesInFlight++;

                //sp修改监听器
                boolean hasListeners = mListeners.size() > 0;
                if (hasListeners) {
                    keysModified = new ArrayList<String>();
//                    WeakHashMap<OnSharedPreferenceChangeListener, Object> mListeners
                    listeners = new HashSet<OnSharedPreferenceChangeListener>(mListeners.keySet());
                }

                synchronized (mEditorLock) {
                    boolean changesMade = false;
                    //清空mMap
                    if (mClear) {
                        if (!mapToWriteToDisk.isEmpty()) {
                            changesMade = true;
                            mapToWriteToDisk.clear();
                        }
                        mClear = false;
                    }
                    for (Map.Entry<String, Object> e : mModified.entrySet()) {
                        String k = e.getKey();
                        Object v = e.getValue();
                        //当值是sp(remove时传值为sp)或者null时执行删除
                        if (v == this || v == null) {
                            //如果原数据里没有改Key则不操作
                            if (!mapToWriteToDisk.containsKey(k)) {
                                continue;
                            }
                            // 删除
                            mapToWriteToDisk.remove(k);
                        } else {
                            if (mapToWriteToDisk.containsKey(k)) {
                                //原数据里包含有修改过Map的Key
                                Object existingValue = mapToWriteToDisk.get(k);
                                if (existingValue != null && existingValue.equals(v)) {
                                    //如果sp里的值没变化则不作保存
                                    continue;
                                }
                            }
                            //将修改过的键值对保存在内存里
                            mapToWriteToDisk.put(k, v);
                        }
                        changesMade = true;
                        if (hasListeners) {
                            //被修改的键池
                            keysModified.add(k);
                        }
                    }
                    //全部保存在内存后清空修改过的Map
                    mModified.clear();
                    if (changesMade) {
                        //内存提交记录+1
                        mCurrentMemoryStateGeneration++;
                    }
                    memoryStateGeneration = mCurrentMemoryStateGeneration;
                }
            }
            return new MemoryCommitResult(memoryStateGeneration, keysModified, listeners,
                    mapToWriteToDisk);
        }

        @Override
        public boolean commit() {
            long startTime = 0;
            if (DEBUG) {
                startTime = System.currentTimeMillis();
            }
            //保存到内存
            MemoryCommitResult mcr = commitToMemory();
            //同步保存到磁盘
            SharedPreferencesImpl.this.enqueueDiskWrite(
                    mcr, null /* sync write on this thread okay */);
            try {
                mcr.writtenToDiskLatch.await();
            } catch (InterruptedException e) {
                return false;
            } finally {
                if (DEBUG) {
                    Log.d(TAG, mFile.getName() + ":" + mcr.memoryStateGeneration
                            + " committed after " + (System.currentTimeMillis() - startTime)
                            + " ms");
                }
            }
            notifyListeners(mcr);
            return mcr.writeToDiskResult;
        }

        private void notifyListeners(final MemoryCommitResult mcr) {
            if (mcr.listeners == null || mcr.keysModified == null ||
                    mcr.keysModified.size() == 0) {
                return;
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                for (int i = mcr.keysModified.size() - 1; i >= 0; i--) {
                    final String key = mcr.keysModified.get(i);
                    for (OnSharedPreferenceChangeListener listener : mcr.listeners) {
                        if (listener != null) {
                            listener.onSharedPreferenceChanged(SharedPreferencesImpl.this, key);
                        }
                    }
                }
            } else {
                // Run this function on the main thread.
                ActivityThread.sMainThreadHandler.post(() -> notifyListeners(mcr));
            }
        }
    }

    /**
     * Enqueue an already-committed-to-memory result to be written
     * to disk.
     * <p>
     * They will be written to disk one-at-a-time in the order
     * that they're enqueued.
     *
     * @param postWriteRunnable if non-null, we're being called
     *                          from apply() and this is the runnable to run after
     *                          the write proceeds.  if null (from a regular commit()),
     *                          then we're allowed to do this disk write on the main
     *                          thread (which in addition to reducing allocations and
     *                          creating a background thread, this has the advantage that
     *                          we catch them in userdebug StrictMode reports to convert
     *                          them where possible to apply() ...)
     */
    private void enqueueDiskWrite(final MemoryCommitResult mcr,
                                  final Runnable postWriteRunnable) {
        //没有runnable则进行同步操作
        final boolean isFromSyncCommit = (postWriteRunnable == null);

        final Runnable writeToDiskRunnable = new Runnable() {
            @Override
            public void run() {
                synchronized (mWritingToDiskLock) {
                    writeToFile(mcr, isFromSyncCommit);
                }
                synchronized (mLock) {
                    mDiskWritesInFlight--;
                }
                if (postWriteRunnable != null) {
                    //从系统队列删除一个任务
                    postWriteRunnable.run();
                }
            }
        };

        // Typical #commit() path with fewer allocations, doing a write on
        // the current thread.
        if (isFromSyncCommit) {
            boolean wasEmpty = false;
            synchronized (mLock) {
                wasEmpty = mDiskWritesInFlight == 1;
            }
            if (wasEmpty) {
                writeToDiskRunnable.run();
                return;
            }
        }

        QueuedWork.queue(writeToDiskRunnable, !isFromSyncCommit);
    }

    private static FileOutputStream createFileOutputStream(File file) {
        FileOutputStream str = null;
        try {
            str = new FileOutputStream(file);
        } catch (FileNotFoundException e) {
            File parent = file.getParentFile();
            if (!parent.mkdir()) {
                Log.e(TAG, "Couldn't create directory for SharedPreferences file " + file);
                return null;
            }
            FileUtils.setPermissions(
                    parent.getPath(),
                    FileUtils.S_IRWXU | FileUtils.S_IRWXG | FileUtils.S_IXOTH,
                    -1, -1);
            try {
                str = new FileOutputStream(file);
            } catch (FileNotFoundException e2) {
                Log.e(TAG, "Couldn't create SharedPreferences file " + file, e2);
            }
        }
        return str;
    }

    @GuardedBy("mWritingToDiskLock")
    private void writeToFile(MemoryCommitResult mcr, boolean isFromSyncCommit) {
        long startTime = 0;
        long existsTime = 0;
        long backupExistsTime = 0;
        long outputStreamCreateTime = 0;
        long writeTime = 0;
        long fsyncTime = 0;
        long setPermTime = 0;
        long fstatTime = 0;
        long deleteTime = 0;

        if (DEBUG) {
            startTime = System.currentTimeMillis();
        }

        boolean fileExists = mFile.exists();

        if (DEBUG) {
            existsTime = System.currentTimeMillis();

            // Might not be set, hence init them to a default value
            backupExistsTime = existsTime;
        }

        // Rename the current file so it may be used as a backup during the next read
        if (fileExists) {
            boolean needsWrite = false;

            // Only need to write if the disk state is older than this commit
            //如果磁盘的状态比提交的老则修改
            if (mDiskStateGeneration < mcr.memoryStateGeneration) {
                if (isFromSyncCommit) {
                    needsWrite = true;
                } else {
                    synchronized (mLock) {
                        // No need to persist intermediate states. Just wait for the latest state to
                        // be persisted.
                        if (mCurrentMemoryStateGeneration == mcr.memoryStateGeneration) {
                            needsWrite = true;
                        }
                    }
                }
            }

            if (!needsWrite) {
                mcr.setDiskWriteResult(false, true);
                return;
            }

            boolean backupFileExists = mBackupFile.exists();

            if (DEBUG) {
                backupExistsTime = System.currentTimeMillis();
            }

            if (!backupFileExists) {
                if (!mFile.renameTo(mBackupFile)) {
                    Log.e(TAG, "Couldn't rename file " + mFile
                            + " to backup file " + mBackupFile);
                    mcr.setDiskWriteResult(false, false);
                    return;
                }
            } else {
                mFile.delete();
            }
        }

        // Attempt to write the file, delete the backup and return true as atomically as
        // possible.  If any exception occurs, delete the new file; next time we will restore
        // from the backup.
        try {
            FileOutputStream str = createFileOutputStream(mFile);

            if (DEBUG) {
                outputStreamCreateTime = System.currentTimeMillis();
            }

            if (str == null) {
                mcr.setDiskWriteResult(false, false);
                return;
            }
            XmlUtils.writeMapXml(mcr.mapToWriteToDisk, str);

            writeTime = System.currentTimeMillis();

            FileUtils.sync(str);

            fsyncTime = System.currentTimeMillis();

            str.close();
            ContextImpl.setFilePermissionsFromMode(mFile.getPath(), mMode, 0);

            if (DEBUG) {
                setPermTime = System.currentTimeMillis();
            }

            try {
                final StructStat stat = Os.stat(mFile.getPath());
                synchronized (mLock) {
                    mStatTimestamp = stat.st_mtim;
                    mStatSize = stat.st_size;
                }
            } catch (ErrnoException e) {
                // Do nothing
            }

            if (DEBUG) {
                fstatTime = System.currentTimeMillis();
            }

            // Writing was successful, delete the backup file if there is one.
            mBackupFile.delete();

            if (DEBUG) {
                deleteTime = System.currentTimeMillis();
            }

            mDiskStateGeneration = mcr.memoryStateGeneration;

            mcr.setDiskWriteResult(true, true);

            if (DEBUG) {
                Log.d(TAG, "write: " + (existsTime - startTime) + "/"
                        + (backupExistsTime - startTime) + "/"
                        + (outputStreamCreateTime - startTime) + "/"
                        + (writeTime - startTime) + "/"
                        + (fsyncTime - startTime) + "/"
                        + (setPermTime - startTime) + "/"
                        + (fstatTime - startTime) + "/"
                        + (deleteTime - startTime));
            }

            long fsyncDuration = fsyncTime - writeTime;
            mSyncTimes.add((int) fsyncDuration);
            mNumSync++;

            if (DEBUG || mNumSync % 1024 == 0 || fsyncDuration > MAX_FSYNC_DURATION_MILLIS) {
                mSyncTimes.log(TAG, "Time required to fsync " + mFile + ": ");
            }

            return;
        } catch (XmlPullParserException e) {
            Log.w(TAG, "writeToFile: Got exception:", e);
        } catch (IOException e) {
            Log.w(TAG, "writeToFile: Got exception:", e);
        }

        // Clean up an unsuccessfully written file
        if (mFile.exists()) {
            if (!mFile.delete()) {
                Log.e(TAG, "Couldn't clean up partially-written file " + mFile);
            }
        }
        mcr.setDiskWriteResult(false, false);
    }
}
