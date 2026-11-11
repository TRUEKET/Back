package com.trueket.backend.analyze;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.trueket.backend.analyze.dto.Stage;
import com.trueket.backend.analyze.service.*;
import com.trueket.backend.global.ai.*;
import com.trueket.backend.global.exception.ApiException;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AnalyzeApiTests {
    private HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger requests = new AtomicInteger();
    private int status = 200;
    private String modelOutput;
    private String requestBody;
    private AnalysisSessionStore store;
    private AnalyzeService service;
    private static final String VALID = """
        {"readable":true,"detected_type":"transfer","type_mismatch":false,
         "sentences":[{"id":"s1","speaker":"SELLER","text":"먼저 01012345678로 연락하고 선입금해 주세요","image_index":1}],
         "signals":[{"id":"S-A2","sentence_id":"s1","quote":"먼저 01012345678로 연락하고 선입금해 주세요","reason":"선입금 요구"}],
         "extracted":{"accounts":[],"phones":["01012345678"],"x_accounts":[],"links":[]},
         "other_suspicious":null,"sensitive_values":["01012345678"]}
        """;

    @BeforeEach void setup() throws Exception {
        modelOutput = VALID;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1beta/interactions", exchange -> {
            requests.incrementAndGet();
            requestBody = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals("fake-test-key", exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            byte[] body = json.writeValueAsBytes(Map.of("status", "completed", "steps", List.of(Map.of(
                    "type", "model_output", "content", List.of(Map.of("type", "text", "text", modelOutput))))));
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        store = new AnalysisSessionStore();
        service = new AnalyzeService(new CaptureProcessor(), new AnalysisPrompt(),
                new GeminiClient("fake-test-key", "test-model", "http://127.0.0.1:" + server.getAddress().getPort()),
                new ModelExtractionParser(), new ChatStageService(new SignalDictionary()), new FinalStageService(),
                new AnalysisReportLookup(), new AnalysisRedactor(), store, Duration.ofSeconds(2));
    }
    @AfterEach void cleanup() { server.stop(0); }

    private List<org.springframework.web.multipart.MultipartFile> captures() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB), "png", output);
        return List.of(new MockMultipartFile("captures", "chat.png", "image/png", output.toByteArray()));
    }

    @Test void imageToRulesMasksOutputAndKeepsSnapshotOwnerBound() throws Exception {
        UUID owner = UUID.randomUUID();
        var result = service.analyze(captures(), "", owner);
        assertTrue(result.readable());
        assertEquals(Stage.DANGER, result.assessment().finalStage());
        assertFalse(json.writeValueAsString(result).contains("01012345678"));
        assertNotNull(result.assessment().lookupWarning());
        assertNotNull(store.get(result.analyzeId(), owner));
        assertThrows(ApiException.class, () -> store.get(result.analyzeId(), UUID.randomUUID()));
        var request = json.readTree(requestBody);
        assertFalse(request.get("store").booleanValue());
        assertEquals("image", request.path("input").get(1).path("type").asText());
        assertEquals("application/json", request.path("response_format").path("mime_type").asText());
        store.delete(result.analyzeId(), owner);
        assertThrows(ApiException.class, () -> store.get(result.analyzeId(), owner));
    }

    @Test void unreadableDoesNotProduceVerdictOrSession() throws Exception {
        modelOutput = "{\"readable\":false}";
        var result = service.analyze(captures(), "", UUID.randomUUID());
        assertFalse(result.readable());
        assertNull(result.analyzeId());
        assertNull(result.assessment());
        assertEquals(1, requests.get());
    }

    @Test void malformedModelOutputRetriesOnceThenFails() throws Exception {
        modelOutput = "not JSON";
        assertThrows(ApiException.class, () -> service.analyze(captures(), "", UUID.randomUUID()));
        assertEquals(2, requests.get());
    }

    @Test void upstreamFailureRetriesOnce() {
        status = 503;
        assertThrows(ApiException.class, () -> service.analyze(captures(), "", UUID.randomUUID()));
        assertEquals(2, requests.get());
    }

    @Test void quotaFailureIsNotRetried() {
        status = 429;
        assertThrows(ApiException.class, () -> service.analyze(captures(), "", UUID.randomUUID()));
        assertEquals(1, requests.get());
    }

    @Test void invalidFilesAndOversizedAdditionalInfoNeverCallModel() throws Exception {
        assertThrows(ApiException.class, () -> service.analyze(List.of(new MockMultipartFile("captures", new byte[]{1,2})), "", UUID.randomUUID()));
        assertThrows(ApiException.class, () -> service.analyze(captures(), "가".repeat(501), UUID.randomUUID()));
        assertThrows(ApiException.class, () -> service.analyze(List.of(), "", UUID.randomUUID()));
        assertEquals(0, requests.get());
    }

    @Test void inventedQuoteCannotIncreaseStage() throws Exception {
        modelOutput = VALID.replace("\"quote\":\"먼저 01012345678로 연락하고 선입금해 주세요\"", "\"quote\":\"없는 문장\"");
        assertEquals(Stage.SAFE, service.analyze(captures(), "", UUID.randomUUID()).assessment().chat().stage());
    }

    @Test void badImageReferenceAndCoercedBooleanAreRejected() {
        var parser = new ModelExtractionParser();
        assertThrows(GeminiException.class, () -> parser.parse(VALID.replace("\"image_index\":1", "\"image_index\":2"), 1));
        assertThrows(GeminiException.class, () -> parser.parse(VALID.replace("\"readable\":true", "\"readable\":\"true\""), 1));
        assertThrows(GeminiException.class, () -> parser.parse(VALID + " {}", 1));
    }

    @Test void missingKeyFailsWithoutNetwork() {
        var client = new GeminiClient("", "test", "http://127.0.0.1:1");
        assertEquals(GeminiException.Kind.CONFIGURATION, assertThrows(GeminiException.class,
                () -> client.generate("test", List.of(), json.createObjectNode(), Duration.ofSeconds(1))).kind());
    }
}
