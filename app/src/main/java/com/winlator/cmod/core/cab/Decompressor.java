package com.winlator.cmod.core.cab;

// One decoder per CAB folder: init once, then one call per data block (CFDATA).
// Same shape as dorkbox CabParser's interface so DecompressLzx fits unchanged
// CAB 폴더 하나에 해제기 하나: 한 번 init한 뒤 데이터 블록(CFDATA)마다 한 번씩 부름.
// DecompressLzx를 그대로 쓰려고 dorkbox CabParser의 인터페이스와 같은 모양으로 둠
public interface Decompressor {
    // Data block size before compression (32 KB)
    // 압축 전 데이터 블록 크기(32KB)
    int CAB_BLOCK_SIZE = 32768;

    void init(int windowBits) throws CabException;

    void decompress(byte[] inputBytes, byte[] outputBytes, int inputLength, int outputLength) throws CabException;

    int getMaxGrowth();

    void reset(int windowBits) throws CabException;
}
