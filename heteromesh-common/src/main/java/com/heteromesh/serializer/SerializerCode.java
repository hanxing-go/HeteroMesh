package com.heteromesh.serializer;

public enum SerializerCode {
    JSON((byte) 0),
    BINARY((byte) 1);

    private final byte code;

    SerializerCode(byte code) {
        this.code = code;
    }

    public byte getCode() {
        return code;
    }

    public static SerializerCode fromCode(byte code) {
        for (SerializerCode sc : values()) {
            if (sc.getCode() == code) {
                return sc;
            }
        }
        throw new IllegalArgumentException("Unkown serializer code: "+ code);
    }
}
