package com.litedata.web;

import com.litedata.governance.*;
import com.litedata.store.PlatformMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/metadata")
public class MetadataController {
    public record SourceOption(Long id,String name) {}
    private final MetadataService metadata;
    private final LineageService lineage;
    private final PlatformMapper platform;
    public MetadataController(MetadataService metadata,LineageService lineage,PlatformMapper platform) {
        this.metadata=metadata; this.lineage=lineage; this.platform=platform;
    }
    @ModelAttribute("activeNav") String activeNav() { return "metadata"; }
    @ModelAttribute("canEdit") boolean canEdit(HttpServletRequest request) { return request.isUserInRole("ADMIN") || request.isUserInRole("DEVELOPER"); }
    @ModelAttribute("canManageUsers") boolean canManageUsers(HttpServletRequest request) { return request.isUserInRole("ADMIN"); }
    @GetMapping String list(@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String layer,
            @RequestParam Map<String,String> params,Model model) {
        var page=ListPagination.prepare(model,"/metadata",params,metadata.count(keyword,layer));
        model.addAttribute("tables",metadata.list(keyword,layer,page)); model.addAttribute("keyword",keyword); model.addAttribute("layer",layer);
        return "metadata";
    }
    @GetMapping("/collect") String collectForm(Model model) {
        model.addAttribute("sources",platform.sources().stream().map(s->new SourceOption(s.getId(),s.getName())).toList());
        return "metadata-collect";
    }
    @PostMapping("/collect") String collect(@RequestParam(required=false) Long sourceId,Principal principal,RedirectAttributes redirect) {
        int count=metadata.collect(sourceId,principal.getName());
        redirect.addFlashAttribute("success","采集完成，共 "+count+" 张表；血缘依据当前任务配置实时生成");
        return "redirect:/metadata";
    }
    @GetMapping("/{id}") String detail(@PathVariable long id,Model model) {
        model.addAttribute("table",metadata.require(id)); model.addAttribute("columns",metadata.columns(id));
        return "metadata-detail";
    }
    @GetMapping("/{id}/lineage") String lineage(@PathVariable long id,@RequestParam(defaultValue="both") String direction,
            @RequestParam(defaultValue="3") int depth,Model model) {
        model.addAttribute("table",metadata.require(id)); model.addAttribute("graph",lineage.graph(id,direction,depth));
        model.addAttribute("direction",direction); model.addAttribute("depth",depth);
        return "metadata-lineage";
    }
}
