package com.obsinity.controller.rest;

import org.springframework.web.bind.annotation.*;

/** Simple query endpoint placeholder. */
@RestController
@RequestMapping("/query")
public class QueryController {
    @GetMapping("/health")
    public String health() {
        return "ok";
    }
}
