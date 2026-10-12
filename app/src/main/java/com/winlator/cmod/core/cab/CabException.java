package com.winlator.cmod.core.cab;

import java.io.IOException;

// A cabinet could not be read (unsupported format or bad data)
// 캐비닛(CAB)을 읽지 못함(지원하지 않는 형식이거나 데이터가 잘못됨)
public class CabException extends IOException {
    public CabException() {
        super("Cabinet error");
    }

    public CabException(String message) {
        super(message);
    }
}
