package com.trueket.backend.trueshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trueket.backend.trueshot.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TrueshotOpenApiTests {
    @Autowired MockMvc mvc;
    @MockitoBean TrueshotFirstPhotoChecker checker;
    @MockitoBean TrueshotOriginalStore originals;
    @MockitoBean TrueshotMaskDetector maskDetector;
    @MockitoBean TrueshotMaskStorage maskStorage;
    @MockitoBean BookingScreenDetector screens;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void anonymousCanReadDocsWithoutOpeningProtectedApis() throws Exception {
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs/all")).andExpect(status().isOk())
                .andExpect(jsonPath("$.info.description").value("TRUEKET 서비스 전체 API 문서입니다. API별 인증 조건과 요청·응답 정보는 각 항목을 확인하세요."))
                .andExpect(jsonPath("$.paths['/auth/login']").exists())
                .andExpect(jsonPath("$.paths['/trueshot']").exists());
        mvc.perform(get("/trueshot")).andExpect(status().isUnauthorized());
        mvc.perform(get("/s/unknown/result")).andExpect(status().isForbidden());
        mvc.perform(post("/s/unknown/start").contentType("application/json")
                .content("{\"sellerConsented\":true}")).andExpect(status().isForbidden());
    }

    @Test
    void trueshotSpecDescribesSessionsCsrfMultipartAndImageResponses() throws Exception {
        var response = mvc.perform(get("/v3/api-docs/trueshot"))
                .andExpect(status().isOk()).andReturn().getResponse();
        JsonNode spec = json.readTree(response.getContentAsString());
        JsonNode paths = spec.path("paths");
        assertEquals(11, paths.size());
        assertTrue(paths.has("/s/{token}/first-mask/confirm"));
        assertFalse(paths.has("/auth/login"));
        assertEquals("인증 사진 업로드", paths.path("/s/{token}/photo").path("post").path("summary").asText());

        JsonNode create = paths.path("/trueshot").path("post");
        assertTrue(create.path("responses").has("201"));
        assertTrue(create.path("security").get(0).has("sessionAuth"));
        boolean csrf = false;
        for (JsonNode parameter : create.path("parameters")) {
            if (parameter.path("name").asText().equals("X-CSRF-TOKEN")) {
                csrf = parameter.path("required").asBoolean()
                        && parameter.path("in").asText().equals("header");
            }
            assertNotEquals("authentication", parameter.path("name").asText());
        }
        assertTrue(csrf);
        assertTrue(paths.path("/s/{token}/start").path("post").path("security").isMissingNode());
        assertTrue(paths.path("/s/{token}/result").path("get").path("security").get(0).has("sessionAuth"));

        JsonNode multipart = paths.path("/s/{token}/photo").path("post")
                .path("requestBody").path("content").path("multipart/form-data");
        assertEquals("application/json", multipart.path("encoding").path("analysis").path("contentType").asText());
        JsonNode schema = multipart.path("schema");
        if (schema.has("$ref")) schema = spec.at(schema.path("$ref").asText().substring(1));
        assertTrue(schema.path("properties").has("photo"));
        assertTrue(schema.path("properties").has("analysis"));

        assertEquals("binary", paths.path("/trueshot/{id}/photos/{number}").path("get")
                .path("responses").path("200").path("content").path("image/jpeg").path("schema").path("format").asText());
        assertFalse(spec.path("components").path("schemas").path("CreateRequest").path("properties")
                .path("title").path("description").asText().isBlank());
    }
}
