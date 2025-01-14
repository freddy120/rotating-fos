/*
 * Copyright 2018-2022 Volkan Yazıcı
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permits and
 * limitations under the License.
 */

package com.vlkan.rfos;

import com.vlkan.rfos.policy.DailyRotationPolicy;
import com.vlkan.rfos.policy.RotationPolicy;
import com.vlkan.rfos.policy.SizeBasedRotationPolicy;
import com.vlkan.rfos.policy.WeeklyRotationPolicy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * A thread-safe {@link OutputStream} targeting a file where rotation of the
 * active stream is supported.
 * <p>
 * Rotation can be triggered by either manually using
 * {@link #rotate(RotationPolicy, Instant)} method or indirectly using the
 * registered {@link RotationPolicy} set.
 * </p><p>
 * Interception of state changes are supported by the registered
 * {@link RotationCallback} set.
 * </p>
 *
 * @see LoggingRotationCallback
 * @see DailyRotationPolicy
 * @see WeeklyRotationPolicy
 * @see SizeBasedRotationPolicy
 */
public class RotatingFileOutputStream extends OutputStream implements Rotatable {

    private static final Logger LOGGER = LogManager.getLogger(RotatingFileOutputStream.class);
    private final RotationConfig config;

    private final List<RotationCallback> callbacks;

    private final List<RotationPolicy> writeSensitivePolicies;

    private volatile ByteCountingOutputStream stream;

    private Pattern sequencePattern = Pattern.compile("(\\$\\{\\s*SEQ\\s*(0+)\\s*})");
    private Pattern sequenceHexPattern = Pattern.compile("(\\$\\{\\s*SEQHEX\\s*(0+)\\s*})");
    private long sequenceCounter;
    private long maxSequenceNum;
    private String sequencePatternStr;
    private String sequencePatternNumberStr;
    private boolean hasSequencePattern;
    private boolean hasSequenceHexPattern;
    private String sequencePatternFormatStr;
    private Instant rotatedInstant;

    /**
     * Constructs an instance using the given configuration
     *
     * @param config a configuration instance
     */
    public RotatingFileOutputStream(RotationConfig config) {
        this(config, config.getSequenceCounter());
    }
    public RotatingFileOutputStream(RotationConfig config, long counter) {
        this.config = Objects.requireNonNull(config, "config");
        this.callbacks = new ArrayList<>(config.getCallbacks());
        this.writeSensitivePolicies = collectWriteSensitivePolicies(config.getPolicies());
        this.stream = open(null, config.getClock().now());
        setSequencePatternStr();
        setSequenceCounter(counter);
        startPolicies();
    }

    private static List<RotationPolicy> collectWriteSensitivePolicies(Set<RotationPolicy> policies) {
        List<RotationPolicy> writeSensitivePolicies = new ArrayList<>();
        for (RotationPolicy policy : policies) {
            if (policy.isWriteSensitive()) {
                writeSensitivePolicies.add(policy);
            }
        }
        return writeSensitivePolicies;
    }

    private void startPolicies() {
        for (RotationPolicy policy : config.getPolicies()) {
            policy.start(this);
        }
    }

    private ByteCountingOutputStream open(RotationPolicy policy, Instant instant) {
        try {
            FileOutputStream fileOutputStream = new FileOutputStream(config.getFile(), config.isAppend());
            invokeCallbacks(callback -> callback.onOpen(policy, instant, fileOutputStream));
            long size = config.isAppend() ? readFileLength() : 0;
            return new ByteCountingOutputStream(fileOutputStream, size);
        } catch (IOException error) {
            String message = String.format("file open failure {file=%s}", config.getFile());
            throw new RuntimeException(message, error);
        }
    }

    @Override
    public void rotate(RotationPolicy policy, Instant instant) {
        try {
            unsafeRotate(policy, instant);
        } catch (Exception error) {
            String message = String.format("rotation failure {instant=%s}", instant);
            RuntimeException extendedError = new RuntimeException(message, error);
            invokeCallbacks(callback -> callback.onFailure(policy, instant, null, extendedError));
        }
    }

    private synchronized void unsafeRotate(RotationPolicy policy, Instant instant) throws Exception {

        // Check arguments.
        Objects.requireNonNull(instant, "instant");

        // Check the state.
        unsafeCheckStream();

        // Notify the trigger listeners.
        invokeCallbacks(callback -> callback.onTrigger(policy, instant));

        // Skip rotation if the file is empty.
        stream.flush();
        if (readFileLength() == 0) {

            if(!config.isRotateEmpty()){
                LOGGER.debug("empty file, skipping rotation {file={}}", config.getFile());
                return;
            }

        }

        // Close the file. (Required before rename on Windows!)
        invokeCallbacks(callback -> callback.onClose(policy, instant, stream));
        stream.close();



        //restart sequence counter if date has changed
        Instant nowInstant = Instant.now();
        LocalDate nowLocalDate = LocalDate.from(nowInstant.atZone(TimeZone.getDefault().toZoneId()));
        if (this.config.getLastLocalDate() == null) {
            sequenceCounter = 0;
            this.config.setSequenceCounter(0);
        } else {
            if (nowLocalDate.isAfter(this.config.getLastLocalDate())) {
                sequenceCounter = 0;
                this.config.setSequenceCounter(0);
            }
        }


        // Backup file, if enabled.
        File rotatedFile;
        if (config.getMaxBackupCount() > 0) {
            renameBackups(config.getFilePermission());
            rotatedFile = backupFile(config.getFilePermission());
        }

        // Otherwise, rename using the provided file pattern.
        else {
            if(hasSequencePattern) {
                String seqToAddStr;
                File rotatedFileTemp;
                seqToAddStr = String.format(sequencePatternFormatStr, sequenceCounter);
                rotatedFileTemp = config.getFilePattern().create(instant, sequencePatternStr, seqToAddStr).getAbsoluteFile();

                while(rotatedFileTemp.exists()) {
                    incrementSequenceCounter();
                    seqToAddStr = String.format(sequencePatternFormatStr, sequenceCounter);

                    rotatedFileTemp = config.getFilePattern().create(instant, sequencePatternStr, seqToAddStr).getAbsoluteFile();
                    if(0 == sequenceCounter)
                        break;
                }
                rotatedFile = rotatedFileTemp;
            } else {
                rotatedFile = config.getFilePattern().create(instant).getAbsoluteFile();
            }
            rotatedInstant = instant;
            LOGGER.debug("renaming {file={}, rotatedFile={}}", config.getFile(), rotatedFile);

            incrementSequenceCounter();//counter must have the next value of the file, not the current one
            this.config.setLastLocalDate(nowLocalDate);
            renameFile(config.getFile(), rotatedFile, config.getFilePermission());
        }

        // Re-open the file.
        LOGGER.debug("re-opening file {file={}}", config.getFile());
        stream = open(policy, instant);
        setPermissionsToFile(config.getFile(), config.getFilePermission());

        // Compress the old file, if necessary.
        if (config.isCompress()) {
            asyncCompress(policy, instant, rotatedFile, config.getFilePermission());
            return;
        }

        // So far, so good;
        invokeCallbacks(callback -> callback.onSuccess(policy, instant, rotatedFile));

    }

    private long readFileLength() throws IOException {
        File file = config.getFile();
        // Reading the file length is a tricky business.
        // We will retry some.
        Exception lastError = null;
        for (int trialIndex = 0; trialIndex < 5; trialIndex++) {
            long fileLength = file.length();
            if (fileLength != 0) {
                return fileLength;
            }
            // `File#length()` can return 0 due to I/O failures.
            // We are falling back to NIO for a second attempt.
            else {
                Path path = file.toPath();
                try {
                    return Files.size(path);
                } catch (IOException error) {
                    lastError = error;
                }
            }
            // Scientifically proven retry practice: wait a bit.
            LockSupport.parkNanos(1);
        }
        String message = String.format("file length read failure {file=%s}", file);
        throw new IOException(message, lastError);
    }

    private void renameBackups() throws IOException {
        renameBackups(null);
    }
    private void renameBackups(String dstFilePermission) throws IOException {
        File dstFile = getBackupFile(config.getMaxBackupCount() - 1);
        for (int backupIndex = config.getMaxBackupCount() - 2; backupIndex >= 0; backupIndex--) {
            File srcFile = getBackupFile(backupIndex);
            if (srcFile.exists()) {
                LOGGER.debug("renaming backup {srcFile={}, dstFile={}}", srcFile, dstFile);
                renameFile(srcFile, dstFile, dstFilePermission);
            }
            dstFile = srcFile;
        }
    }

    private File backupFile() throws IOException {
        return backupFile(null);
    }
    private File backupFile(String dstFilePermission) throws IOException {
        File dstFile = getBackupFile(0);
        File srcFile = config.getFile();
        LOGGER.debug("renaming for backup {srcFile={}, dstFile={}}", srcFile, dstFile);
        renameFile(srcFile, dstFile, dstFilePermission);
        return dstFile;
    }

    private static void renameFile(File srcFile, File dstFile, String dstFilePermission) throws IOException {
        Files.move(
                srcFile.toPath(),
                dstFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING/*,      // The rest of the arguments (atomic & copy-attr) are pretty
                StandardCopyOption.ATOMIC_MOVE,             // much platform-dependent and JVM throws an "unsupported
                StandardCopyOption.COPY_ATTRIBUTES*/);      // option" exception at runtime.

        setPermissionsToFile(dstFile, dstFilePermission);
    }

    private File getBackupFile(int backupIndex) {
        String parent = config.getFile().getParent();
        if (parent == null) {
            parent = ".";
        }
        String fileName = config.getFile().getName() + '.' + backupIndex;
        return Paths.get(parent, fileName).toFile();
    }

    private void asyncCompress(RotationPolicy policy, Instant instant, File rotatedFile, String dstFilePermission) {
        config.getExecutorService().execute(new Runnable() {

            private final String displayName =
                    String.format(
                            "%s.compress(%s)",
                            RotatingFileOutputStream.class.getSimpleName(), rotatedFile);

            @Override
            public void run() {
                File compressedFile = getCompressedFile(rotatedFile);

                setPermissionsToFile(compressedFile, dstFilePermission);

                try {
                    unsafeSyncCompress(rotatedFile, compressedFile);
                    invokeCallbacks(callback -> callback.onSuccess(policy, instant, compressedFile));
                } catch (Exception error) {
                    String message = String.format(
                            "compression failure {instant=%s, rotatedFile=%s, compressedFile=%s}",
                            instant, rotatedFile, compressedFile);
                    RuntimeException extendedError = new RuntimeException(message, error);
                    invokeCallbacks(callback -> callback.onFailure(policy, instant, rotatedFile, extendedError));
                }
            }

            @Override
            public String toString() {
                return displayName;
            }

        });
    }

    private File getCompressedFile(File rotatedFile) {
        String compressedFileName = String.format("%s.gz", rotatedFile.getAbsolutePath());
        return new File(compressedFileName);
    }

    private static void unsafeSyncCompress(File rotatedFile, File compressedFile) throws IOException {

        // Compress the file.
        LOGGER.debug("compressing {rotatedFile={}, compressedFile={}}", rotatedFile, compressedFile);
        try (InputStream sourceStream = new FileInputStream(rotatedFile)) {
            try (FileOutputStream targetStream = new FileOutputStream(compressedFile);
                 GZIPOutputStream gzipTargetStream = new GZIPOutputStream(targetStream)) {
                copy(sourceStream, gzipTargetStream);
            }
        }

        // Delete the rotated file. (On Windows, delete must take place after closing the file input stream!)
        LOGGER.debug("deleting old file {rotatedFile={}}", rotatedFile);
        boolean deleted = rotatedFile.delete();
        if (!deleted) {
            String message = String.format("failed deleting old file {rotatedFile=%s}", rotatedFile);
            throw new IOException(message);
        }

    }

    private static void copy(InputStream source, OutputStream target) throws IOException {
        byte[] buffer = new byte[8192];
        int readByteCount;
        while ((readByteCount = source.read(buffer)) > 0) {
            target.write(buffer, 0, readByteCount);
        }
    }

    @Override
    public RotationConfig getConfig() {
        return config;
    }

    @Override
    public synchronized void write(int b) throws IOException {
        unsafeCheckStream();
        long byteCount = stream.size() + 1;
        notifyWriteSensitivePolicies(byteCount);
        stream.write(b);
    }

    @Override
    public synchronized void write(byte[] b) throws IOException {
        unsafeCheckStream();
        long byteCount = stream.size() + b.length;
        notifyWriteSensitivePolicies(byteCount);
        stream.write(b);
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) throws IOException {
        unsafeCheckStream();
        long byteCount = stream.size() + len;
        notifyWriteSensitivePolicies(byteCount);
        stream.write(b, off, len);
    }

    private void notifyWriteSensitivePolicies(long byteCount) {
        // noinspection ForLoopReplaceableByForEach (avoid iterator instantion)
        for (int writeSensitivePolicyIndex = 0;
             writeSensitivePolicyIndex < writeSensitivePolicies.size();
             writeSensitivePolicyIndex++) {
            RotationPolicy writeSensitivePolicy = writeSensitivePolicies.get(writeSensitivePolicyIndex);
            writeSensitivePolicy.acceptWrite(byteCount);
        }
    }

    @Override
    public synchronized void flush() throws IOException {
        if (stream != null) {
            stream.flush();
        }
    }

    /**
     * Unless the stream is already closed, invokes registered callbacks,
     * stops registered policies, and closes the active stream.
     */
    @Override
    public synchronized void close() throws IOException {
        if (stream == null) {
            return;
        }
        invokeCallbacks(callback -> callback.onClose(null, config.getClock().now(), stream));
        stopPolicies();
        stream.close();
        stream = null;
    }

    private void stopPolicies() {
        config.getPolicies().forEach(RotationPolicy::stop);
    }

    private void invokeCallbacks(Consumer<RotationCallback> invoker) {
        // noinspection ForLoopReplaceableByForEach (avoid iterator instantion)
        for (int callbackIndex = 0; callbackIndex < callbacks.size(); callbackIndex++) {
            RotationCallback callback = callbacks.get(callbackIndex);
            invoker.accept(callback);
        }
    }

    private void unsafeCheckStream() throws IOException {
        if (stream == null) {
            throw new IOException("either closed or not initialized yet");
        }
    }

    @Override
    public String toString() {
        return String.format("RotatingFileOutputStream{file=%s}", config.getFile());
    }

    private void incrementSequenceCounter() {
        sequenceCounter++;
        if(sequenceCounter > maxSequenceNum) {
            sequenceCounter = 0;
        }
        this.config.setSequenceCounter(sequenceCounter);

    }
    private void setSequencePatternStr() {
        String pattern = this.config.getFilePattern().getPattern();
        Matcher matcher = sequencePattern.matcher(pattern);
        if(matcher.find()) {
            this.sequencePatternStr = matcher.group(1);
            this.sequencePatternNumberStr = matcher.group(2);
            maxSequenceNum = Long.parseLong("1" + sequencePatternNumberStr) - 1;
            sequencePatternFormatStr = "%0" + sequencePatternNumberStr.length() + "d";
            this.hasSequencePattern = true;
        } else {
            pattern = this.config.getFilePattern().getPattern();
            matcher = sequenceHexPattern.matcher(pattern);
            if(matcher.find()) {

                this.sequencePatternStr = matcher.group(1);
                this.sequencePatternNumberStr = matcher.group(2);
                maxSequenceNum = (long) (Math.pow(2, sequencePatternNumberStr.length()*4) -1);
                sequencePatternFormatStr = "%0" + sequencePatternNumberStr.length() + "X";

                this.hasSequenceHexPattern = true;
                this.hasSequencePattern = true;
            }else{
                this.hasSequencePattern = false;

            }
        }
    }
    private void setSequenceCounter(long counter) {
        sequenceCounter = counter;
        this.config.setSequenceCounter(sequenceCounter);
    }
    public long getSequenceCounter() {
        return sequenceCounter;
    }
    public Instant getRotatedInstant() {
        return rotatedInstant;
    }

    public static void setPermissionsToFile(File file, String permission) {
        try {
            if(permission != null && FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Set<PosixFilePermission> filePermissions = PosixFilePermissions.fromString(permission);
                Files.setPosixFilePermissions(file.toPath(), filePermissions);
            }
        } catch (Exception e) {
            LOGGER.debug("Cannot assign permissions {} to dst file: {}", permission, file);
        }
    }
}
