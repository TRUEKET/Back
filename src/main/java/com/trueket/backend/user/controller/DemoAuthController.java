package com.trueket.backend.user.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("demo")
public class DemoAuthController {
    @GetMapping(value = "/auth/demo", produces = "text/html;charset=UTF-8")
    public ResponseEntity<Resource> demo() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ClassPathResource("demo/auth.html"));
    }
}