package com.litedata.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SettingsController {
    @GetMapping("/settings")
    String settings(HttpServletRequest request, Model model) {
        model.addAttribute("activeNav", "settings");
        model.addAttribute("canManageUsers", request.isUserInRole("ADMIN"));
        return "settings";
    }
}
