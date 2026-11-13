package com.trueket.backend.global.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"analysis.ai-provider=openai","openai.api-key=fake-openai-key","openai.model=test-model",
    "spring.datasource.url=jdbc:h2:mem:openai-flow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class OpenAiFlowTests {
    static final ObjectMapper JSON=new ObjectMapper();
    static final AtomicInteger CALLS=new AtomicInteger();
    static volatile int statusCode=200;
    static volatile boolean recover=false;
    static volatile String outputMode="completed";
    static volatile String capturedBody;
    static final HttpServer API;
    static final String EXTRACTION="""
        {"readable":true,"detected_type":"transfer","type_mismatch":false,
         "sentences":[{"id":"s1","speaker":"SELLER","text":"01012345678로 온 인증번호 알려주세요","image_index":1}],
         "signals":[{"id":"S-A2","sentence_id":"s1","quote":"01012345678로 온 인증번호 알려주세요","reason":"인증번호 요구"}],
         "extracted":{"accounts":[],"phones":["01012345678"],"x_accounts":[],"links":[]},
         "other_suspicious":null,"sensitive_values":["01012345678"]}
        """;
    static {
        try {
            API=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            API.createContext("/v1/responses",exchange -> {
                int call=CALLS.incrementAndGet();
                capturedBody=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                assertEquals("Bearer fake-openai-key",exchange.getRequestHeaders().getFirst("Authorization"));
                assertNull(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
                int status=(recover && call>1)?200:statusCode;
                Object content=List.of(Map.of("type","output_text","text",EXTRACTION));
                if(outputMode.equals("refusal")) content=List.of(Map.of("type","refusal","refusal","upstream private details fake-openai-key"));
                Map<String,Object> body=Map.of("status",outputMode.equals("incomplete")?"incomplete":"completed",
                    "output",List.of(Map.of("type","reasoning","summary",List.of()),
                        Map.of("type","message","role","assistant","content",content)));
                byte[] encoded=outputMode.equals("malformed")?"not-json".getBytes():JSON.writeValueAsBytes(body);
                exchange.sendResponseHeaders(status,encoded.length);
                exchange.getResponseBody().write(encoded); exchange.close();
            });
            API.start();
        } catch(Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void endpoint(DynamicPropertyRegistry properties) {
        properties.add("openai.base-url",()->"http://127.0.0.1:"+API.getAddress().getPort()+"/v1");
    }
    @AfterAll static void stop() { API.stop(0); }
    @BeforeEach void reset() { CALLS.set(0);statusCode=200;recover=false;outputMode="completed";capturedBody=null; }
    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;

    private ResultActions analyze() throws Exception {
        var initial=mvc.perform(get("/auth/csrf")).andReturn();
        var session=(MockHttpSession)initial.getRequest().getSession();
        var csrf=(CsrfToken)initial.getRequest().getAttribute(CsrfToken.class.getName());
        var png=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(20,20,BufferedImage.TYPE_INT_RGB),"png",png);
        return mvc.perform(multipart("/analyze").file(new MockMultipartFile("captures","chat.png","image/png",png.toByteArray()))
            .session(session).header(csrf.getHeaderName(),csrf.getToken()));
    }

    @Test void selectsOnlyOpenAiAndUsesImagesStrictSchemaAndNoStore() throws Exception {
        assertEquals(1,context.getBeansOfType(AnalysisModelClient.class).size());
        assertInstanceOf(OpenAiClient.class,context.getBean(AnalysisModelClient.class));
        analyze().andExpect(status().isOk()).andExpect(jsonPath("$.assessment.finalStage").value("DANGER"))
            .andExpect(jsonPath("$.conversations.images[0].imageNumber").value(1))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("01012345678"))));
        var body=JSON.readTree(capturedBody);
        assertFalse(body.path("store").booleanValue());
        assertEquals("low",body.path("reasoning").path("effort").asText());
        assertEquals(8192,body.path("max_output_tokens").asInt());
        assertTrue(body.path("text").path("format").path("strict").booleanValue());
        assertEquals("json_schema",body.path("text").path("format").path("type").asText());
        assertTrue(body.path("text").path("format").path("schema").path("properties").path("signals")
            .path("items").path("properties").path("id").path("enum").toString().contains("S-A2"));
        var image=body.path("input").get(0).path("content").get(1);
        assertEquals("input_image",image.path("type").asText());
        assertTrue(image.path("image_url").asText().startsWith("data:image/png;base64,"));
    }

    @Test void upstreamFailureRetriesOnceThenUsesServerRules() throws Exception {
        statusCode=503;recover=true;
        analyze().andExpect(status().isOk()).andExpect(jsonPath("$.assessment.finalStage").value("DANGER"));
        assertEquals(2,CALLS.get());
    }
    @Test void quotaDoesNotRetryOrSwitchProvider() throws Exception {
        statusCode=429;
        analyze().andExpect(status().isTooManyRequests());
        assertEquals(1,CALLS.get());
    }
    @Test void invalidKeyDoesNotRetryAndNeverExposesProviderText() throws Exception {
        statusCode=401;
        analyze().andExpect(status().isServiceUnavailable())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("fake-openai-key"))));
        assertEquals(1,CALLS.get());
    }
    @Test void refusalDoesNotRetryOrReturnSafeVerdict() throws Exception {
        outputMode="refusal";
        analyze().andExpect(status().isBadGateway()).andExpect(jsonPath("$.assessment").doesNotExist())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private details"))));
        assertEquals(1,CALLS.get());
    }
    @Test void incompleteDoesNotRetryOrReturnSafeVerdict() throws Exception {
        outputMode="incomplete";
        analyze().andExpect(status().isBadGateway()).andExpect(jsonPath("$.assessment").doesNotExist());
        assertEquals(1,CALLS.get());
    }
    @Test void malformedJsonRetriesAtMostOnce() throws Exception {
        outputMode="malformed";
        analyze().andExpect(status().isBadGateway());
        assertEquals(2,CALLS.get());
    }
    @Test void missingConfigurationFailsBeforeNetwork() {
        var client=new OpenAiClient("","test-model","http://127.0.0.1:1/v1","low",8192);
        assertEquals(ModelException.Kind.CONFIGURATION,assertThrows(ModelException.class,
            ()->client.generate("test",List.of(),JSON.createObjectNode(),Duration.ofSeconds(1))).kind());
        assertEquals(0,CALLS.get());
    }
}
