package com.litedata.web;

import com.litedata.store.PlatformMapper;
import java.security.Principal;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class UserController {
    private final PlatformMapper mapper; private final PasswordEncoder encoder;
    public UserController(PlatformMapper mapper,PasswordEncoder encoder) { this.mapper=mapper; this.encoder=encoder; }
    @GetMapping("/users") String list(Model model) {
        model.addAttribute("users",mapper.users()); model.addAttribute("activeNav","users"); model.addAttribute("canManageUsers",true); return "users";
    }
    @PostMapping("/users") String create(@RequestParam String username,@RequestParam String password,@RequestParam String role,
            RedirectAttributes redirect,Principal principal) {
        if(!username.matches("[a-zA-Z][a-zA-Z0-9_-]{2,59}") || password.length()<12 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72 ||
            !Set.of("ADMIN","DEVELOPER","VIEWER").contains(role)) {
            redirect.addFlashAttribute("error","用户名3到60字符；密码12到72字符；角色须为内置角色");
        } else if(mapper.findUser(username)!=null) redirect.addFlashAttribute("error","用户名已存在");
        else {
            mapper.insertUser(username,encoder.encode(password),role); mapper.audit(principal.getName(),"USER_CREATE",username,"SUCCESS");
            redirect.addFlashAttribute("success","用户已创建");
        }
        return "redirect:/users";
    }
}

