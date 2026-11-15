package com.trueket.backend.analyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:ownedapidocs;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","dispute.mail.enabled=false"})
@AutoConfigureMockMvc
class OwnedApiOpenApiTests {
    @Autowired MockMvc mvc;
    final ObjectMapper json=new ObjectMapper();
    JsonNode spec(String group) throws Exception {
        return json.readTree(mvc.perform(get("/v3/api-docs/"+group)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
    boolean csrf(JsonNode operation) {
        for(var p:operation.path("parameters")) if(p.path("name").asText().equals("X-CSRF-TOKEN"))
            return p.path("in").asText().equals("header") && p.path("required").asBoolean()
                    && p.path("schema").path("type").asText().equals("string");
        return false;
    }
    @Test void accountsGroupDocumentsAllAccountActionsAndFilterLogout() throws Exception {
        var doc=spec("accounts");var paths=doc.path("paths");
        assertEquals(12,paths.size());
        assertTrue(paths.has("/auth/logout"));assertFalse(paths.has("/trueshot"));
        assertFalse(paths.has("/me/reports"));assertFalse(paths.has("/me/reports/{id}"));
        assertFalse(paths.has("/auth/demo"));assertFalse(paths.has("/analyze"));
        for(String path:new String[]{"/auth/login","/auth/signup","/auth/phone/send","/auth/phone/verify","/auth/logout","/me/phone/send","/me/phone/verify"}) {
            var op=paths.path(path).path("post");assertTrue(csrf(op),path);
            assertFalse(op.path("summary").asText().isBlank(),path);
        }
        for(String path:new String[]{"/me/phone","/me/password"})assertTrue(csrf(paths.path(path).path("patch")));
        assertTrue(csrf(paths.path("/me").path("delete")));
        assertTrue(paths.path("/auth/logout").path("post").path("responses").has("204"));
        assertTrue(paths.path("/me").path("delete").path("security").get(0).has("sessionAuth"));
        assertTrue(paths.path("/auth/csrf").path("get").path("parameters").isMissingNode());
        assertTrue(doc.path("components").path("schemas").path("Signup").path("properties").path("password").path("writeOnly").asBoolean());
    }
    @Test void analysisHasRealFileArrayAndAnonymousSessionCsrfContract() throws Exception {
        var doc=spec("analysis");var paths=doc.path("paths");assertEquals(3,paths.size());
        var post=paths.path("/analyze").path("post");
        assertTrue(csrf(post));assertTrue(post.path("security").isMissingNode());
        var schema=post.path("requestBody").path("content").path("multipart/form-data").path("schema");
        if(schema.has("$ref"))schema=doc.path("components").path("schemas").path(schema.path("$ref").asText().replace("#/components/schemas/",""));
        assertEquals("array",schema.path("properties").path("captures").path("type").asText());
        assertEquals("binary",schema.path("properties").path("captures").path("items").path("format").asText());
        assertTrue(schema.path("properties").has("additionalInfo"));
        for(String code:new String[]{"200","400","403","413","429","502","503","504"})assertTrue(post.path("responses").has(code),code);
        assertFalse(post.path("responses").path("400").has("content"));
        var feedback=paths.path("/analyze/{id}/feedback").path("post");assertTrue(csrf(feedback));
        assertTrue(feedback.path("responses").has("201"));assertTrue(feedback.path("responses").has("409"));
        assertTrue(paths.path("/analyze/{id}").path("delete").path("responses").has("204"));
    }
    @Test void docsRemainPublicAndDoNotDisableSecurity() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        var config=json.readTree(mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(config.path("urls").toString().contains("accounts"));assertTrue(config.path("urls").toString().contains("analysis"));
        assertTrue(spec("all").path("paths").has("/auth/logout"));
        mvc.perform(get("/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/login").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }
}
