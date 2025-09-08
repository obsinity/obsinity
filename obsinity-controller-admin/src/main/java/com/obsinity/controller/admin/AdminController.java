package com.obsinity.controller.admin;

import org.springframework.web.bind.annotation.*;

/** Admin/ops endpoints placeholder. */
@RestController
@RequestMapping("/admin")
public class AdminController {
    @PostMapping("/retention/run")
    public String runRetention() {
        // TODO: trigger retention job
        return "retention-triggered";
    }
}
