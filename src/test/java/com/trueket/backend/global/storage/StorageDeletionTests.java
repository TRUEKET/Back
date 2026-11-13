package com.trueket.backend.global.storage;

import com.sun.net.httpserver.HttpServer;
import com.trueket.backend.global.exception.ApiException;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class StorageDeletionTests {
    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private String response = "[]";
    private SupabaseStorageDeletion storage;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/storage/v1/object/",exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body=response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        storage=new SupabaseStorageDeletion("http://127.0.0.1:"+server.getAddress().getPort(),"fake-service-key","evidence","masked","original");
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void validatesWholePlanBeforeDeletingAnyFiles() {
        for(String invalid:List.of("../other.png","folder//photo.png","https://elsewhere.example/photo.png","folder/%2e%2e/photo.png","folder/*.png")) {
            assertThrows(ApiException.class,()->storage.deleteAll(List.of(
                new StorageTarget(StorageArea.EVIDENCE,"own/valid.png"),
                new StorageTarget(StorageArea.TRUESHOT_MASKED,invalid))));
        }
        assertEquals(0,calls.get());
    }

    @Test void httpSuccessWithErrorBodyIsNotSuccessfulDeletion() {
        response="{\"error\":\"not removed\"}";
        assertThrows(ApiException.class,()->storage.deleteAll(List.of(new StorageTarget(StorageArea.EVIDENCE,"own/photo.png"))));
        assertEquals(1,calls.get());
    }
}
