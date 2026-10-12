package com.winlator.cmod.core.cab;

// The compressed data inside a cabinet is broken
// 캐비닛 안의 압축 데이터가 깨져 있음
public class CorruptCabException extends CabException {
    public CorruptCabException() {
        super("Corrupt cabinet data");
    }

    public CorruptCabException(String message) {
        super(message);
    }
}
