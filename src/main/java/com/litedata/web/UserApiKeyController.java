package com.litedata.web;

import com.litedata.security.UserApiKeyService;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Self-service lifecycle for MCP credentials. Plaintext keys never enter a redirect or session. */
@Controller
public class UserApiKeyController {
    private final UserApiKeyService keys;

    public UserApiKeyController(UserApiKeyService keys) { this.keys = keys; }

    @ModelAttribute("activeNav") String activeNav() { return "api-keys"; }

    @ModelAttribute("canManageUsers") boolean canManageUsers(jakarta.servlet.http.HttpServletRequest request) {
        return request.isUserInRole("ADMIN");
    }

    @InitBinder("draft") void bind(WebDataBinder binder) {
        binder.setAllowedFields("name", "validDays", "scopes", "serviceSlugs", "pipelineIds");
    }

    @GetMapping("/settings/api-keys")
    String list(@RequestParam Map<String, String> params, Model model, Principal principal, HttpServletResponse response) {
        noStore(response);
        var page = ListPagination.prepare(model, "/settings/api-keys", params, keys.count(principal.getName()));
        model.addAttribute("keys", keys.list(principal.getName(), page));
        model.addAttribute("now", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        return "api-keys";
    }

    @GetMapping("/settings/api-keys/new")
    String createForm(Model model, Principal principal, HttpServletResponse response) {
        noStore(response);
        var draft = new ApiKeyDraft();
        draft.setScopes(new String[]{"services:read", "services:query"});
        return form(model, principal.getName(), draft);
    }

    @PostMapping("/settings/api-keys")
    String create(@ModelAttribute("draft") ApiKeyDraft draft, BindingResult errors, Model model,
                  Principal principal, HttpServletResponse response) {
        noStore(response);
        try {
            if (errors.hasErrors()) throw new IllegalArgumentException();
            var issued = keys.create(principal.getName(), draft.name, draft.validDays, normalizedScopes(draft.scopes),
                    draft.serviceSlugs, draft.pipelineIds);
            model.addAttribute("issued", issued);
            return "api-key-created";
        } catch (IllegalArgumentException exception) {
            model.addAttribute("error", "请检查名称、有效期、权限范围和资源标识。");
            return form(model, principal.getName(), draft);
        }
    }

    @PostMapping("/settings/api-keys/{id}/revoke")
    String revoke(@PathVariable long id, Principal principal, RedirectAttributes redirect, HttpServletResponse response) {
        noStore(response);
        keys.revoke(principal.getName(), id);
        redirect.addFlashAttribute("success", "API Key 已吊销。");
        return "redirect:/settings/api-keys";
    }

    private String form(Model model, String username, ApiKeyDraft draft) {
        model.addAttribute("draft", draft);
        model.addAttribute("allowedScopes", keys.allowedScopes(username));
        return "api-key-form";
    }

    private Set<String> normalizedScopes(String[] scopes) {
        if (scopes == null) return Set.of();
        var result = new LinkedHashSet<String>();
        for (String scope : scopes) if (scope != null && !scope.isBlank()) result.add(scope);
        return Set.copyOf(result);
    }

    private void noStore(HttpServletResponse response) { response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store"); }

    public static class ApiKeyDraft {
        private String name = "";
        private int validDays = 90;
        private String[] scopes = {};
        private String serviceSlugs = "";
        private String pipelineIds = "";
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getValidDays() { return validDays; }
        public void setValidDays(int validDays) { this.validDays = validDays; }
        public String[] getScopes() { return scopes; }
        public void setScopes(String[] scopes) { this.scopes = scopes; }
        public String getServiceSlugs() { return serviceSlugs; }
        public void setServiceSlugs(String serviceSlugs) { this.serviceSlugs = serviceSlugs; }
        public String getPipelineIds() { return pipelineIds; }
        public void setPipelineIds(String pipelineIds) { this.pipelineIds = pipelineIds; }
    }
}
