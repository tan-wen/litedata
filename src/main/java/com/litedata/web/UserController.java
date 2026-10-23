package com.litedata.web;

import com.litedata.store.PlatformMapper;
import com.litedata.web.ResourceForms.UserDraft;
import java.security.Principal;
import java.util.Set;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class UserController {
    private final PlatformMapper mapper; private final PasswordEncoder encoder;
    public UserController(PlatformMapper mapper,PasswordEncoder encoder) { this.mapper=mapper; this.encoder=encoder; }
    @ModelAttribute("activeNav") String activeNav() { return "users"; }
    @ModelAttribute("canManageUsers") boolean canManageUsers() { return true; }
    @InitBinder("draft") void bind(WebDataBinder binder) { binder.setAllowedFields("username","password","role"); }
    @GetMapping("/users") String list(@RequestParam java.util.Map<String,String> params,Model model) {
        var page=ListPagination.prepare(model,"/users",params,mapper.userCount());
        model.addAttribute("users",mapper.userPage(page.pageSize(),page.offset())); return "users";
    }
    @GetMapping("/users/new") String create(Model model) { return form(model,new UserDraft(),null); }
    @GetMapping("/users/{id}/edit") String edit(@PathVariable long id,Model model) {
        var draft=new UserDraft(); BeanUtils.copyProperties(require(id),draft);
        return form(model,draft,id);
    }
    @PostMapping("/users") String create(@ModelAttribute("draft") UserDraft draft,BindingResult errors,
            Model model,RedirectAttributes redirect,Principal principal) {
        return save(null,draft,errors,model,redirect,principal);
    }
    @PostMapping("/users/{id}") String update(@PathVariable long id,@ModelAttribute("draft") UserDraft draft,BindingResult errors,
            Model model,RedirectAttributes redirect,Principal principal) {
        require(id); return save(id,draft,errors,model,redirect,principal);
    }
    private String save(Long id,UserDraft draft,BindingResult errors,Model model,RedirectAttributes redirect,Principal principal) {
        try {
            String password=draft.getPassword();
            if(errors.hasErrors() || draft.getUsername()==null || !draft.getUsername().matches("[a-zA-Z][a-zA-Z0-9_-]{2,59}") ||
                    password==null || ((id==null || !password.isEmpty()) && (password.length()<12 ||
                    password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)) || draft.getRole()==null ||
                    !Set.of("ADMIN","DEVELOPER","VIEWER").contains(draft.getRole()))
                throw new IllegalArgumentException("用户名3到60字符；密码至少12字符且不超过72字节；角色须为内置角色");
            if(id==null) {
                mapper.insertUser(draft.getUsername(),encoder.encode(password),draft.getRole());
            } else {
                var account=require(id);
                if(!account.getUsername().equals(draft.getUsername())) throw new IllegalArgumentException("用户名创建后不可修改");
                if(account.getUsername().equals(principal.getName()) && !"ADMIN".equals(draft.getRole()))
                    throw new IllegalArgumentException("不能移除当前登录账号的管理员角色");
                account.setRole(draft.getRole());
                if(!password.isEmpty()) account.setPasswordHash(encoder.encode(password));
                mapper.updateUser(account);
            }
            mapper.audit(principal.getName(),id==null?"USER_CREATE":"USER_UPDATE",draft.getUsername(),"SUCCESS");
            redirect.addFlashAttribute("success",id==null?"用户已创建":"用户已更新，权限和密码在下次登录时生效");
            return "redirect:/users";
        } catch(IllegalArgumentException exception) { model.addAttribute("error",exception.getMessage()); }
        catch(DuplicateKeyException exception) { model.addAttribute("error","用户名已存在"); }
        mapper.audit(principal.getName(),"PAGE_MUTATION",id==null?"/users":"/users/"+id,"FAILED");
        draft.setPassword("");
        return form(model,draft,id);
    }
    private com.litedata.model.AppUser require(long id) {
        var account=mapper.user(id);
        if(account==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"用户不存在");
        return account;
    }
    private String form(Model model,UserDraft draft,Long id) {
        model.addAttribute("draft",draft); model.addAttribute("resourceId",id);
        model.addAttribute("formAction",id==null?"/users":"/users/"+id);
        return "user-form";
    }
}

