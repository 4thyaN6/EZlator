package com.winlator.cmod.alsaserver;

import com.winlator.cmod.sysvshm.SysVSharedMemory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class ALSAClient {
    public enum DataType {
        U8(1), S16LE(2), S16BE(2), FLOATLE(4), FLOATBE(4);
        public final byte byteCount;

        DataType(int byteCount) {
            this.byteCount = (byte)byteCount;
        }
    }
    private DataType dataType = DataType.U8;
    private byte channelCount = 2;
    private int sampleRate = 0;
    private int position;
    private int bufferSize;
    private int frameBytes;
    private ByteBuffer sharedBuffer;
    private boolean playing = false;
    private long streamPtr = 0;
    private long mirrorStreamPtr = 0;

    private boolean reflectorMode;

    static {
        System.loadLibrary("winlator");
    }

    // BUG FIX (logcat 2026-09-28 23:40/23:42, also 09-24/09-27): the app crashed in
    // AudioTrack::write right after "AAudioStream_close(s#4)". The stream was closed
    // on another thread (shutdown / device change) while the ALSA request thread was
    // still inside write() on it - a use-after-close. All stream-touching methods
    // are now synchronized on this client, so close waits for an in-flight write
    // and a later write sees playing=false / streamPtr=0 and does nothing.
    // 버그 수정(로그캣 2026-09-28 23:40/23:42, 09-24/09-27에도 발생): "AAudioStream_close
    // (s#4)" 직후 AudioTrack::write 안에서 앱이 죽었음. ALSA 요청 스레드가 아직 그
    // 스트림에 write() 중인데 다른 스레드(종료/장치 변경)가 스트림을 닫은 것 - 닫힌
    // 뒤 사용임. 스트림을 다루는 메서드를 전부 이 클라이언트 기준으로 동기화해서,
    // 닫기는 진행 중인 write가 끝날 때까지 기다리고, 그 뒤 write는 playing=false /
    // streamPtr=0을 보고 아무것도 안 함
    // Audio guard marker (see XServerDisplayActivity.applyAudioFallback): "active"
    // while an ALSA stream is open, "failed" if a stream could not be opened. A
    // native crash leaves the file behind, which is how the next launch knows.
    // 오디오 감시 표식(XServerDisplayActivity.applyAudioFallback 참고): ALSA 스트림이
    // 열려 있는 동안 "active", 스트림을 못 열었으면 "failed". 네이티브 크래시가 나면
    // 파일이 남아서, 다음 실행이 그걸로 알아챔
    private static volatile java.io.File audioGuardMarker;

    public static void setAudioGuardMarker(java.io.File marker) {
        audioGuardMarker = marker;
    }

    private static void markAudio(String state) {
        java.io.File f = audioGuardMarker;
        if (f == null) return;
        try {
            java.nio.file.Files.write(f.toPath(), state.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    public synchronized void release() {
        if (sharedBuffer != null) {
            SysVSharedMemory.unmapSHMSegment(sharedBuffer, sharedBuffer.capacity());
            sharedBuffer = null;
        }

        if (reflectorMode) {
            simulatedStop(streamPtr);
            stop(mirrorStreamPtr);
            simulatedClose(streamPtr);
            close(mirrorStreamPtr);
        } else {
            stop(streamPtr);
            close(streamPtr);
        }

        playing = false;
        streamPtr = 0;
        mirrorStreamPtr = 0;
    }

    public synchronized void prepare() {
        position = 0;
        frameBytes = channelCount * dataType.byteCount;
        release();

        if (!isValidBufferSize()) return;

        // BUG FIX (app crash SIGSEGV in AudioTrack::write, logcat 2026-09-24 and
        // 2026-09-27): U8 streams were opened with AAUDIO_FORMAT_UNSPECIFIED, so
        // AAudio picked its own 2- or 4-byte sample format while the data here is
        // 1 byte per sample. AAudioStream_write then read 2-4x past the end of
        // the shared buffer and crashed the whole app. U8 is now opened as S16LE
        // and converted in writeDataToStream().
        // 버그 수정(앱 크래시: AudioTrack::write 안의 SIGSEGV, 로그캣 2026-09-24,
        // 2026-09-27): U8 스트림을 AAUDIO_FORMAT_UNSPECIFIED로 열어서 AAudio가
        // 2바이트나 4바이트 샘플 형식을 스스로 골랐는데, 여기 데이터는 샘플당
        // 1바이트였음. 그래서 AAudioStream_write가 공유 버퍼 끝을 2~4배 넘어 읽다가
        // 앱 전체가 죽었음. 이제 U8은 S16LE로 열고 writeDataToStream()에서 변환함
        int streamFormat = dataType == DataType.U8 ? DataType.S16LE.ordinal() : dataType.ordinal();

        if (reflectorMode) {
            // In reflector mode, create both the pacer and the real stream
            streamPtr = simulatedCreate(streamFormat, channelCount, sampleRate, bufferSize);
            mirrorStreamPtr = create(streamFormat, channelCount, sampleRate, bufferSize);
        } else {
            // In normal mode, create only the real stream and assign it to streamPtr
            streamPtr = create(streamFormat, channelCount, sampleRate, bufferSize);
            mirrorStreamPtr = 0; // Ensure mirror is null
        }

        boolean opened = streamPtr > 0 && (!reflectorMode || mirrorStreamPtr > 0);
        markAudio(opened ? "active" : "failed");
        if (streamPtr > 0) start();
    }

    public synchronized void start() {
        if (streamPtr > 0 && !playing) {
            if (reflectorMode) {
                simulatedStart(streamPtr);
                start(mirrorStreamPtr);
            } else {
                start(streamPtr);
            }
            playing = true;
        }
    }

    public synchronized void stop() {
        if (streamPtr > 0 && playing) {
            if (reflectorMode) {
                simulatedStop(streamPtr);
                stop(mirrorStreamPtr);
            } else {
                stop(streamPtr);
            }
            playing = false;
        }
    }

    public synchronized void pause() {
        if (streamPtr > 0) {
            if (reflectorMode) {
                simulatedPause(streamPtr);
                pause(mirrorStreamPtr);
            } else {
                pause(streamPtr);
            }
            playing = false;
        }
    }

    public synchronized void drain() {
        if (streamPtr > 0) {
            if (reflectorMode) {
                simulatedFlush(streamPtr);
                flush(mirrorStreamPtr);
            } else {
                flush(streamPtr);
            }
        }
    }

    // Reused S16 buffer for U8 conversion / U8 변환용 S16 버퍼(재사용)
    private ByteBuffer u8ConvertBuffer;

    // Unsigned 8-bit -> signed 16-bit little-endian: (b - 128) << 8
    // 부호 없는 8비트 -> 부호 있는 16비트 리틀엔디언: (b - 128) << 8
    private ByteBuffer convertU8ToS16(ByteBuffer src) {
        int samples = src.limit() - src.position();
        int needed = samples * 2;
        if (u8ConvertBuffer == null || u8ConvertBuffer.capacity() < needed) {
            u8ConvertBuffer = ByteBuffer.allocateDirect(Math.max(needed, 4096)).order(ByteOrder.LITTLE_ENDIAN);
        }
        u8ConvertBuffer.clear();
        for (int i = src.position(); i < src.limit(); i++) {
            u8ConvertBuffer.putShort((short) (((src.get(i) & 0xFF) - 128) << 8));
        }
        u8ConvertBuffer.flip();
        return u8ConvertBuffer;
    }

    public synchronized void writeDataToStream(ByteBuffer data) {
        // Stream already closed -> drop the data instead of writing to freed memory
        // 스트림이 이미 닫혔으면 해제된 메모리에 쓰지 않고 데이터를 버림
        if (streamPtr <= 0 || (reflectorMode && mirrorStreamPtr <= 0)) {
            data.rewind();
            return;
        }
        if (dataType == DataType.U8) {
            // Frame count stays the same; only the sample width doubles
            // 프레임 수는 그대로이고 샘플 폭만 두 배가 됨
            int numFramesU8 = data.limit() / frameBytes;
            ByteBuffer s16 = convertU8ToS16(data);
            if (playing) {
                if (reflectorMode) {
                    int framesWritten = simulatedWrite(streamPtr, s16, numFramesU8);
                    write(mirrorStreamPtr, s16, numFramesU8);
                    if (framesWritten > 0) position += framesWritten;
                } else {
                    int framesWritten = write(streamPtr, s16, numFramesU8);
                    if (framesWritten > 0) position += framesWritten;
                }
            }
            data.rewind();
            return;
        }
        ByteBuffer mirrorData = data.duplicate();

        if (dataType == DataType.S16LE || dataType == DataType.FLOATLE) {
            data.order(ByteOrder.LITTLE_ENDIAN);
            mirrorData.order(ByteOrder.LITTLE_ENDIAN);
        } else if (dataType == DataType.S16BE || dataType == DataType.FLOATBE) {
            data.order(ByteOrder.BIG_ENDIAN);
            mirrorData.order(ByteOrder.BIG_ENDIAN);
        }

        int numFrames = data.limit() / frameBytes;

        if (playing) {
            if (reflectorMode) {
                // Reflector Mode: Write to pacer, mirror to real stream
                int framesWritten = simulatedWrite(streamPtr, data, numFrames);
                write(mirrorStreamPtr, mirrorData, numFrames);
                if (framesWritten > 0) position += framesWritten;
                data.rewind();
                mirrorData.rewind();
            } else {
                // Normal Mode: Write directly to the real stream
                int framesWritten = write(streamPtr, data, numFrames);
                if (framesWritten > 0) position += framesWritten;
                data.rewind();
            }
        }
    }

    /**
     * This is the new method to handle audio device changes.
     * It will be called from the Android system when a device is connected or disconnected.
     */
    public synchronized void onAudioDeviceChanged() {
        // This entire feature is designed for reflector mode.
        // If we are in normal mode, the underlying connection to Wine is
        // likely broken, and we should do nothing.
        if (!reflectorMode) {
            System.out.println("Audio device change detected, but reflector mode is off. Ignoring.");
            return;
        }

        // --- REFLECTOR MODE LOGIC ---
        // The pacer (streamPtr) is safe. We only need to rebuild the hardware stream (mirrorStreamPtr).
        if (mirrorStreamPtr != 0) {
            System.out.println("Tearing down old playback stream...");
            stop(mirrorStreamPtr);
            close(mirrorStreamPtr);
            mirrorStreamPtr = 0;
        }

        final int MAX_RETRIES = 5;
        final int RETRY_DELAY_MS = 200;

        for (int i = 0; i < MAX_RETRIES; i++) {
            System.out.println("Attempting to rebuild playback stream (Attempt " + (i + 1) + ")");
            // Same U8 -> S16LE mapping as prepare() (UNSPECIFIED would over-read again)
            // prepare()와 같은 U8 -> S16LE 매핑(UNSPECIFIED면 다시 초과 읽기 발생)
            int fmt = dataType == DataType.U8 ? DataType.S16LE.ordinal() : dataType.ordinal();
            long newStreamPtr = create(fmt, channelCount, sampleRate, bufferSize);

            if (newStreamPtr > 0) {
                if (playing) {
                    int result = start(newStreamPtr);
                    if (result == 0) { // AAUDIO_OK
                        mirrorStreamPtr = newStreamPtr;
                        System.out.println("Successfully resumed playback on new stream.");
                        return;
                    } else {
                        System.out.println("Failed to start new stream, result code: " + result + ". Retrying...");
                        close(newStreamPtr);
                    }
                } else {
                    mirrorStreamPtr = newStreamPtr;
                    System.out.println("Successfully created new stream while paused.");
                    return;
                }
            }
            try {
                Thread.sleep(RETRY_DELAY_MS);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        System.out.println("Failed to rebuild playback stream after " + MAX_RETRIES + " attempts.");
    }

    public int pointer() {
        return position;
    }

    public void setDataType(DataType dataType) {
        this.dataType = dataType;
    }

    public void setChannelCount(int channelCount) {
        this.channelCount = (byte)channelCount;
    }

    public void setSampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    public void setBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
    }

    public ByteBuffer getSharedBuffer() {
        return sharedBuffer;
    }

    public void setSharedBuffer(ByteBuffer sharedBuffer) {
        this.sharedBuffer = sharedBuffer;
    }

    public DataType getDataType() {
        return dataType;
    }

    public byte getChannelCount() {
        return channelCount;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getBufferSize() {
        return bufferSize;
    }

    public int getBufferSizeInBytes() {
        return bufferSize * frameBytes;
    }

    private boolean isValidBufferSize() {
        return (getBufferSizeInBytes() % frameBytes == 0) && bufferSize > 0;
    }

    public int computeLatencyMillis() {
        return (int)(((float)bufferSize / sampleRate) * 1000);
    }

    public void setReflectorMode(boolean enabled) {
        this.reflectorMode = enabled;
    }

    private native long simulatedCreate(int format, byte channelCount, int sampleRate, int bufferSize);
    private native long create(int format, byte channelCount, int sampleRate, int mirrorBufferSize);
    private native int simulatedWrite(long streamPtr, ByteBuffer buffer, int numberFrames);
    private native int write(long mirrorStreamPtr, ByteBuffer mirrorBuffer, int numFrames);
    private native void simulatedStart(long streamPtr);
    private native int start(long mirrorStreamPtr);

    private native void simulatedStop(long streamPtr);
    private native void stop(long mirrorStreamPtr);

    private native void simulatedPause(long streamPtr);
    private native void pause(long mirrorStreamPtr);

    private native void simulatedFlush(long streamPtr);
    private native void flush(long mirrorStreamPtr);

    private native void simulatedClose(long streamPtr);
    private native void close(long mirrorStreamPtr);
}