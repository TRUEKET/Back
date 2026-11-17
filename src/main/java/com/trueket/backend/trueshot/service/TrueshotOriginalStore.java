package com.trueket.backend.trueshot.service;

public interface TrueshotOriginalStore {
    void upload(String key, byte[] bytes, String mimeType);
}
